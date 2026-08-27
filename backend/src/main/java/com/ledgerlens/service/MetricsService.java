package com.ledgerlens.service;

import com.ledgerlens.dto.AnswerKey;
import com.ledgerlens.dto.CalibrationBucket;
import com.ledgerlens.dto.MetricsReport;
import com.ledgerlens.entity.ExceptionRecord;
import com.ledgerlens.entity.ExceptionStatus;
import com.ledgerlens.repository.AnomalyAlertRepository;
import com.ledgerlens.repository.ExceptionRecordRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scores the detected exceptions against the anomalies the generator injected.
 *
 * <p>Predictions and ground truth are joined on the entity reference — an order id for order-level
 * findings, a UTR for bank-level ones — so a finding of the right type against the wrong record
 * counts as both a false positive and a false negative rather than quietly passing.
 *
 * <p>Precision and recall say how often the detector is right; the calibration buckets say whether
 * it knows when it is. A detector that stamps everything 0.95 and is right 60% of the time scores
 * the same precision as one that admits doubt, and only the buckets tell them apart.
 */
@Service
public class MetricsService {

    private static final BigDecimal ZERO = new BigDecimal("0.0000");
    private static final BigDecimal ONE = new BigDecimal("1.0000");
    private static final BigDecimal[] BUCKET_EDGES = {
            new BigDecimal("0.00"), new BigDecimal("0.50"), new BigDecimal("0.70"),
            new BigDecimal("0.90"), new BigDecimal("1.00")
    };

    private final IngestBatchRepository ingestBatchRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final AnomalyAlertRepository alertRepository;
    private final String answerKeyPath;

    public MetricsService(IngestBatchRepository ingestBatchRepository,
                          ExceptionRecordRepository exceptionRepository,
                          AnomalyAlertRepository alertRepository,
                          @Value("${ledgerlens.answer-key-path:data/answer_key.json}") String answerKeyPath) {
        this.ingestBatchRepository = ingestBatchRepository;
        this.exceptionRepository = exceptionRepository;
        this.alertRepository = alertRepository;
        this.answerKeyPath = answerKeyPath;
    }

    @Transactional(readOnly = true)
    public MetricsReport metrics(UUID batchId) {
        if (!ingestBatchRepository.existsById(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown batch " + batchId);
        }
        List<ExceptionRecord> detected = exceptionRepository.findByBatchIdOrderById(batchId);

        Path path = Path.of(answerKeyPath);
        if (!Files.isRegularFile(path)) {
            return new MetricsReport(batchId, false, path.toString(), detected.size(), 0, Map.of(), null, List.of(), null);
        }
        AnswerKey answerKey = readAnswerKey(path);

        Map<ExceptionStatus, Set<String>> predicted = new HashMap<>();
        detected.forEach(record -> predicted
                .computeIfAbsent(record.getStatus(), status -> new HashSet<>())
                .add(record.getEntityRef()));

        Map<ExceptionStatus, Set<String>> expected = new HashMap<>();
        answerKey.anomalies().forEach(anomaly -> expected
                .computeIfAbsent(anomaly.type(), status -> new HashSet<>())
                .add(anomaly.orderId() != null ? anomaly.orderId() : anomaly.utr()));

        Map<String, MetricsReport.TypeMetrics> byType = new LinkedHashMap<>();
        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        for (ExceptionStatus status : ExceptionStatus.values()) {
            if (status == ExceptionStatus.MATCHED) {
                continue;
            }
            Set<String> predictedRefs = predicted.getOrDefault(status, Set.of());
            Set<String> expectedRefs = expected.getOrDefault(status, Set.of());
            int hits = (int) predictedRefs.stream().filter(expectedRefs::contains).count();
            int misfires = predictedRefs.size() - hits;
            int missed = expectedRefs.size() - hits;
            truePositives += hits;
            falsePositives += misfires;
            falseNegatives += missed;
            byType.put(status.name(), score(hits, misfires, missed));
        }

        return new MetricsReport(batchId, true, path.toString(), detected.size(),
                answerKey.anomalies().size(), byType,
                score(truePositives, falsePositives, falseNegatives),
                calibrate(detected, expected),
                scoreAlerts(batchId, answerKey));
    }
    /**
     * Grades the monitor's alerts against the degradations the generator deliberately applied. Only
     * meaningful for a synthetic batch that recorded them; a real batch has no such ground truth and
     * gets null rather than a fabricated score.
     */
    private MetricsReport.TypeMetrics scoreAlerts(UUID batchId, AnswerKey answerKey) {
        if (answerKey.batchAnomalies() == null || answerKey.batchAnomalies().isEmpty()) {
            return null;
        }
        Set<String> expected = new HashSet<>();
        answerKey.batchAnomalies().forEach(anomaly -> expected.add(anomaly.metric()));
        Set<String> raised = new HashSet<>();
        alertRepository.findByBatchIdOrderById(batchId).forEach(alert -> raised.add(alert.getMetric()));

        int hits = (int) raised.stream().filter(expected::contains).count();
        return score(hits, raised.size() - hits, expected.size() - hits);
    }


    /**
     * Buckets findings by stated confidence and reports how often each bucket was actually right, so
     * over-confidence shows up as a gap between the mean confidence and the observed accuracy.
     */
    private static List<CalibrationBucket> calibrate(List<ExceptionRecord> detected,
                                                     Map<ExceptionStatus, Set<String>> expected) {
        List<CalibrationBucket> buckets = new ArrayList<>();
        for (int i = 0; i < BUCKET_EDGES.length - 1; i++) {
            BigDecimal lower = BUCKET_EDGES[i];
            BigDecimal upper = BUCKET_EDGES[i + 1];
            boolean last = i == BUCKET_EDGES.length - 2;

            int count = 0;
            int correct = 0;
            BigDecimal confidenceSum = BigDecimal.ZERO;
            for (ExceptionRecord record : detected) {
                BigDecimal confidence = record.getConfidence();
                boolean inBucket = confidence.compareTo(lower) >= 0
                        && (last ? confidence.compareTo(upper) <= 0 : confidence.compareTo(upper) < 0);
                if (!inBucket) {
                    continue;
                }
                count++;
                confidenceSum = confidenceSum.add(confidence);
                if (expected.getOrDefault(record.getStatus(), Set.of()).contains(record.getEntityRef())) {
                    correct++;
                }
            }

            buckets.add(new CalibrationBucket(lower, upper, count, correct,
                    count == 0 ? null : confidenceSum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP),
                    count == 0 ? null : BigDecimal.valueOf(correct)
                            .divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP)));
        }
        return buckets;
    }

    private static MetricsReport.TypeMetrics score(int truePositives, int falsePositives, int falseNegatives) {
        BigDecimal precision = ratio(truePositives, truePositives + falsePositives, falseNegatives == 0);
        BigDecimal recall = ratio(truePositives, truePositives + falseNegatives, falsePositives == 0);
        BigDecimal f1 = precision.signum() == 0 && recall.signum() == 0
                ? ZERO
                : precision.multiply(recall).multiply(BigDecimal.TWO)
                        .divide(precision.add(recall), 4, RoundingMode.HALF_UP);
        return new MetricsReport.TypeMetrics(truePositives, falsePositives, falseNegatives, precision, recall, f1);
    }

    /** With nothing to score, a status is perfect only if nothing was missed either. */
    private static BigDecimal ratio(int hits, int total, boolean vacuouslyPerfect) {
        if (total == 0) {
            return vacuouslyPerfect ? ONE : ZERO;
        }
        return BigDecimal.valueOf(hits).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private static AnswerKey readAnswerKey(Path path) {
        try {
            return SyntheticDataWriter.answerKeyMapper().readValue(path.toFile(), AnswerKey.class);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read answer key at " + path, e);
        }
    }
}
