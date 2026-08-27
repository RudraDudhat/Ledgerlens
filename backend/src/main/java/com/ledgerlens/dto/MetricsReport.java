package com.ledgerlens.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How the detected exceptions score against the injected ones. Scored per status and micro-averaged
 * across all of them, so a status the reconciler never predicts still costs it recall.
 *
 * @param answerKeyPresent false when no answer key was found, in which case the scores are absent
 *                         and only the detected counts are reported
 */
public record MetricsReport(
        UUID batchId,
        boolean answerKeyPresent,
        String answerKeySource,
        int detectedCount,
        int expectedCount,
        Map<String, TypeMetrics> byType,
        TypeMetrics overall,
        List<CalibrationBucket> calibration,
        TypeMetrics alertScore) {

    public record TypeMetrics(
            int truePositives,
            int falsePositives,
            int falseNegatives,
            BigDecimal precision,
            BigDecimal recall,
            BigDecimal f1) {
    }
}
