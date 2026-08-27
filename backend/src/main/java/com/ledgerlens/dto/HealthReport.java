package com.ledgerlens.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param baseline           the trailing median of earlier batches, or null when there are too few
 * @param priorBatchCount    how many batches the baseline was drawn from
 * @param insufficientHistory true until two earlier batches exist; no alerts are raised before that,
 *                            because a "baseline" of one is just the previous number
 */
public record HealthReport(
        UUID batchId,
        BatchMetrics metrics,
        BatchMetrics baseline,
        int priorBatchCount,
        boolean insufficientHistory,
        List<AlertView> alerts) {
}
