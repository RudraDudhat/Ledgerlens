package com.ledgerlens.service;

import com.ledgerlens.dto.AlertView;
import com.ledgerlens.dto.BatchMetrics;
import com.ledgerlens.dto.HealthHistoryPoint;
import com.ledgerlens.dto.HealthReport;
import com.ledgerlens.entity.AnomalyAlert;
import com.ledgerlens.repository.AnomalyAlertRepository;
import com.ledgerlens.repository.BatchHealthRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Assembles what the Health strip needs: this batch's metrics, its baseline, and what it raised. */
@Service
public class HealthService {

    private final IngestBatchRepository ingestBatchRepository;
    private final BatchHealthRepository batchHealthRepository;
    private final AnomalyAlertRepository alertRepository;
    private final BatchHealthService batchHealthService;
    private final AnomalyDetector anomalyDetector;

    public HealthService(IngestBatchRepository ingestBatchRepository,
                         BatchHealthRepository batchHealthRepository,
                         AnomalyAlertRepository alertRepository,
                         BatchHealthService batchHealthService,
                         AnomalyDetector anomalyDetector) {
        this.ingestBatchRepository = ingestBatchRepository;
        this.batchHealthRepository = batchHealthRepository;
        this.alertRepository = alertRepository;
        this.batchHealthService = batchHealthService;
        this.anomalyDetector = anomalyDetector;
    }

    /** Runs after a reconcile: measure the batch, then compare it with what came before. */
    @Transactional
    public BatchMetrics measure(UUID batchId) {
        BatchMetrics metrics = batchHealthService.computeAndStore(batchId);
        anomalyDetector.detect(batchId, metrics, batchHealthService.priorMetrics(batchId));
        return metrics;
    }

    @Transactional(readOnly = true)
    public HealthReport report(UUID batchId) {
        requireBatch(batchId);
        BatchMetrics metrics = batchHealthRepository.findByBatchId(batchId)
                .map(row -> batchHealthService.read(row.getMetrics()))
                .orElseGet(() -> batchHealthService.compute(batchId));

        List<BatchMetrics> prior = batchHealthService.priorMetrics(batchId);
        boolean insufficient = prior.size() < AnomalyDetector.MIN_PRIOR_BATCHES;

        List<AlertView> alerts = alertRepository.findByBatchIdOrderById(batchId).stream()
                .map(HealthService::view)
                .toList();

        return new HealthReport(batchId, metrics, insufficient ? null : baselineOf(prior),
                prior.size(), insufficient, alerts);
    }

    @Transactional(readOnly = true)
    public List<HealthHistoryPoint> history(UUID batchId, int limit) {
        requireBatch(batchId);
        return batchHealthService.history(limit);
    }

    /** The same trailing median the detector alerts on, so the strip and the alerts agree. */
    private static BatchMetrics baselineOf(List<BatchMetrics> prior) {
        return new BatchMetrics(
                medianOf(prior, BatchMetrics::feeRate),
                medianOf(prior, BatchMetrics::failureRate),
                medianOfMaps(prior, BatchMetrics::failureRateByMethod),
                medianOfMaps(prior, BatchMetrics::failureRateByHour),
                medianOf(prior, BatchMetrics::disputeRate),
                medianOf(prior, BatchMetrics::matchRate),
                medianOfMaps(prior, BatchMetrics::settlementDelayDaysByMethod),
                medianOf(prior, BatchMetrics::avgSettlementDelayDays),
                prior.isEmpty() ? 0 : prior.get(prior.size() - 1).orderCount());
    }

    private static BigDecimal medianOf(List<BatchMetrics> prior, Function<BatchMetrics, BigDecimal> read) {
        BigDecimal median = AnomalyDetector.median(prior.stream().map(read).toList());
        return median == null ? BigDecimal.ZERO : median;
    }

    private static Map<String, BigDecimal> medianOfMaps(List<BatchMetrics> prior,
                                                        Function<BatchMetrics, Map<String, BigDecimal>> read) {
        Map<String, List<BigDecimal>> collected = new LinkedHashMap<>();
        for (BatchMetrics metrics : prior) {
            read.apply(metrics).forEach((key, value) ->
                    collected.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value));
        }
        Map<String, BigDecimal> medians = new LinkedHashMap<>();
        collected.forEach((key, values) -> {
            BigDecimal median = AnomalyDetector.median(values);
            medians.put(key, median == null ? BigDecimal.ZERO : median);
        });
        return medians;
    }

    private static AlertView view(AnomalyAlert alert) {
        return new AlertView(
                alert.getId(),
                alert.getMetric(),
                alert.getCurrentValue(),
                alert.getBaselineValue(),
                alert.getRatio(),
                alert.getSeverity().name(),
                alert.getSourceRowIds() == null ? List.of() : alert.getSourceRowIds(),
                alert.getLikelyCause(),
                alert.getSuggestedCheck(),
                alert.getConfidence());
    }

    private void requireBatch(UUID batchId) {
        if (!ingestBatchRepository.existsById(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown batch " + batchId);
        }
    }
}
