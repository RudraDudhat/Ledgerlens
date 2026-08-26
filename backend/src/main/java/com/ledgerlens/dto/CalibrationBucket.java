package com.ledgerlens.dto;

import java.math.BigDecimal;

/**
 * Whether stated confidence matches observed accuracy. A well calibrated detector puts roughly as
 * many correct findings in a bucket as its confidence claims: 90% of the findings it calls 0.9
 * should be right.
 *
 * @param observedAccuracy correct divided by count, or null when the bucket is empty
 */
public record CalibrationBucket(
        BigDecimal lowerBound,
        BigDecimal upperBound,
        int count,
        int correct,
        BigDecimal meanConfidence,
        BigDecimal observedAccuracy) {
}
