package com.ledgerlens.service;

import com.ledgerlens.dto.AlertExplanation;
import com.ledgerlens.dto.BatchMetrics;
import com.ledgerlens.entity.AlertSeverity;
import com.ledgerlens.entity.AnomalyAlert;
import com.ledgerlens.entity.AuditLog;
import com.ledgerlens.entity.Payment;
import com.ledgerlens.entity.PaymentStatus;
import com.ledgerlens.repository.AnomalyAlertRepository;
import com.ledgerlens.repository.AuditLogRepository;
import com.ledgerlens.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Compares a batch against its own history and raises what moved.
 *
 * <p>The baseline is a trailing median, not a mean, because one catastrophic week would otherwise
 * drag the baseline far enough to hide the next one. Nothing is raised until two earlier batches
 * exist: a "baseline" drawn from a single batch is just the previous number, and alerting on it
 * would produce a warning for every ordinary week-to-week wobble.
 *
 * <p>Rules decide entirely whether an alert exists and how severe it is. The model is given the
 * finished alert and a sample of the rows behind it, and may only suggest a cause and a check.
 */
@Service
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    public static final int MIN_PRIOR_BATCHES = 2;
    static final BigDecimal SPIKE_RATIO = new BigDecimal("2");
    static final BigDecimal HIGH_RATIO = new BigDecimal("3");
    /** An hour is only interesting relative to the batch it sits in, not to history. */
    static final BigDecimal HOUR_SPIKE_RATIO = new BigDecimal("3");
    private static final int MAX_SOURCE_ROWS = 50;
    private static final int MAX_SAMPLE_ROWS = 10;
    private static final int SCALE = 6;

    private record Tracked(String name, Function<BatchMetrics, BigDecimal> read, boolean dropOnly) {
    }

    private static final List<Tracked> TRACKED = List.of(
            new Tracked("fee_rate", BatchMetrics::feeRate, false),
            new Tracked("failure_rate", BatchMetrics::failureRate, false),
            new Tracked("dispute_rate", BatchMetrics::disputeRate, false),
            new Tracked("avg_settlement_delay_days", BatchMetrics::avgSettlementDelayDays, false),
            // A match rate that doubles is good news; only a collapse is worth waking anyone for.
            new Tracked("match_rate", BatchMetrics::matchRate, true));

    private final PaymentRepository paymentRepository;
    private final AnomalyAlertRepository alertRepository;
    private final AuditLogRepository auditLogRepository;
    private final LlmGateway llm;
    private final String template;

    public AnomalyDetector(PaymentRepository paymentRepository,
                           AnomalyAlertRepository alertRepository,
                           AuditLogRepository auditLogRepository,
                           LlmGateway llm) {
        this.paymentRepository = paymentRepository;
        this.alertRepository = alertRepository;
        this.auditLogRepository = auditLogRepository;
        this.llm = llm;
        this.template = LlmGateway.loadPrompt("anomaly-explainer.txt");
    }

    @Transactional
    public List<AnomalyAlert> detect(UUID batchId, BatchMetrics current, List<BatchMetrics> prior) {
        alertRepository.deleteByBatchId(batchId);
        alertRepository.flush();

        if (prior.size() < MIN_PRIOR_BATCHES) {
            return List.of();
        }

        List<AnomalyAlert> alerts = new ArrayList<>();
        for (Tracked tracked : TRACKED) {
            BigDecimal baseline = median(prior.stream().map(tracked.read()).toList());
            BigDecimal value = tracked.read().apply(current);
            if (baseline == null || baseline.signum() == 0) {
                continue;
            }
            BigDecimal ratio = value.divide(baseline, SCALE, RoundingMode.HALF_UP);

            boolean spiked = !tracked.dropOnly() && ratio.compareTo(SPIKE_RATIO) > 0;
            boolean collapsed = ratio.compareTo(new BigDecimal("0.5")) < 0;
            if (!spiked && !collapsed) {
                continue;
            }
            alerts.add(alert(batchId, tracked.name(), value, baseline, ratio, severityOf(ratio),
                    rowsFor(batchId, tracked.name())));
        }

        alerts.addAll(hourAlerts(batchId, current));

        alerts.forEach(this::explain);
        List<AnomalyAlert> saved = alertRepository.saveAll(alerts);
        saved.forEach(alert -> auditLogRepository.save(auditEntry(alert)));
        return saved;
    }

    /**
     * A single hour whose failure rate is several times the batch's own is worth flagging even when
     * the batch overall looks normal, which is exactly how a nightly outage hides.
     */
    private List<AnomalyAlert> hourAlerts(UUID batchId, BatchMetrics current) {
        BigDecimal overall = current.failureRate();
        if (overall.signum() == 0) {
            return List.of();
        }
        BigDecimal threshold = overall.multiply(HOUR_SPIKE_RATIO);

        List<AnomalyAlert> alerts = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : current.failureRateByHour().entrySet()) {
            if (entry.getValue().compareTo(threshold) <= 0) {
                continue;
            }
            int hour = Integer.parseInt(entry.getKey());
            BigDecimal ratio = entry.getValue().divide(overall, SCALE, RoundingMode.HALF_UP);
            alerts.add(alert(batchId, "failure_rate_hour_%02d".formatted(hour), entry.getValue(), overall, ratio,
                    severityOf(ratio), failedPaymentRowsInHour(batchId, hour)));
        }
        return alerts;
    }

    private void explain(AnomalyAlert alert) {
        if (!llm.available()) {
            return;
        }
        String prompt = template
                .replace("{{alert}}", describe(alert))
                .replace("{{rows}}", String.join("\n", sampleRows(alert)));
        try {
            AlertExplanation explanation =
                    llm.completeAs(alert.getBatchId(), "LLM_EXPLAIN_ALERT", prompt, AlertExplanation.class);
            if (explanation == null || explanation.likelyCause() == null || explanation.likelyCause().isBlank()) {
                return;
            }
            alert.setLikelyCause(trim(explanation.likelyCause(), 500));
            alert.setSuggestedCheck(trim(explanation.suggestedCheck(), 300));
            alert.setConfidence(BigDecimal.valueOf(Math.max(0, Math.min(1, explanation.confidence())))
                    .setScale(3, RoundingMode.HALF_UP));
        } catch (RuntimeException e) {
            // The numbers stand on their own; only the commentary is lost.
            log.warn("could not explain {}: {}", alert.getMetric(), e.toString());
        }
    }

    private List<String> sampleRows(AnomalyAlert alert) {
        List<Long> ids = alert.getSourceRowIds() == null ? List.of() : alert.getSourceRowIds();
        List<String> rows = new ArrayList<>();
        for (Payment payment : paymentRepository.findAllById(ids.stream().limit(MAX_SAMPLE_ROWS).toList())) {
            rows.add("payment id=%d method=%s status=%s amount=%s fee=%s at=%s"
                    .formatted(payment.getId(), payment.getMethod(), payment.getStatus(),
                            payment.getAmount(), payment.getFee(), payment.getCreatedAt()));
        }
        return rows;
    }

    /** The rows a reviewer would look at first for this metric, capped so an alert stays readable. */
    private List<Long> rowsFor(UUID batchId, String metric) {
        List<Payment> payments = paymentRepository.findByBatchIdOrderById(batchId);
        List<Long> ids = new ArrayList<>();
        for (Payment payment : payments) {
            boolean relevant = switch (metric) {
                case "failure_rate" -> payment.getStatus() == PaymentStatus.FAILED;
                case "fee_rate" -> payment.getFee().signum() > 0;
                default -> true;
            };
            if (relevant && ids.size() < MAX_SOURCE_ROWS) {
                ids.add(payment.getId());
            }
        }
        return ids;
    }

    private List<Long> failedPaymentRowsInHour(UUID batchId, int hour) {
        List<Long> ids = new ArrayList<>();
        for (Payment payment : paymentRepository.findByBatchIdOrderById(batchId)) {
            if (payment.getStatus() == PaymentStatus.FAILED
                    && payment.getCreatedAt().getHour() == hour
                    && ids.size() < MAX_SOURCE_ROWS) {
                ids.add(payment.getId());
            }
        }
        return ids;
    }

    static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle))
                .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
    }

    /** Two to three times the baseline warns; beyond that, or a matching collapse, is high. */
    static AlertSeverity severityOf(BigDecimal ratio) {
        if (ratio.compareTo(HIGH_RATIO) > 0) {
            return AlertSeverity.HIGH;
        }
        if (ratio.signum() > 0 && ratio.compareTo(new BigDecimal("0.34")) < 0) {
            return AlertSeverity.HIGH;
        }
        return AlertSeverity.WARN;
    }

    private static AnomalyAlert alert(UUID batchId, String metric, BigDecimal value, BigDecimal baseline,
                                      BigDecimal ratio, AlertSeverity severity, List<Long> sourceRowIds) {
        AnomalyAlert alert = new AnomalyAlert();
        alert.setBatchId(batchId);
        alert.setMetric(metric);
        alert.setCurrentValue(value.setScale(SCALE, RoundingMode.HALF_UP));
        alert.setBaselineValue(baseline.setScale(SCALE, RoundingMode.HALF_UP));
        alert.setRatio(ratio.setScale(SCALE, RoundingMode.HALF_UP));
        alert.setSeverity(severity);
        alert.setSourceRowIds(sourceRowIds);
        alert.setCreatedAt(LocalDateTime.now());
        return alert;
    }

    private static String describe(AnomalyAlert alert) {
        return "{\"metric\":\"%s\",\"current\":%s,\"baseline\":%s,\"ratio\":%s,\"severity\":\"%s\"}"
                .formatted(alert.getMetric(), alert.getCurrentValue(), alert.getBaselineValue(),
                        alert.getRatio(), alert.getSeverity());
    }

    private static AuditLog auditEntry(AnomalyAlert alert) {
        AuditLog entry = new AuditLog();
        entry.setLoggedAt(LocalDateTime.now());
        entry.setBatchId(alert.getBatchId());
        entry.setAction("ANOMALY_ALERT");
        entry.setDetail("metric=%s current=%s baseline=%s ratio=%s severity=%s rows=%d"
                .formatted(alert.getMetric(), alert.getCurrentValue(), alert.getBaselineValue(),
                        alert.getRatio(), alert.getSeverity(),
                        alert.getSourceRowIds() == null ? 0 : alert.getSourceRowIds().size()));
        return entry;
    }

    private static String trim(String value, int limit) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }

    static String hourMetricName(int hour) {
        return "failure_rate_hour_%02d".formatted(hour).toLowerCase(Locale.ROOT);
    }
}
