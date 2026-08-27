package com.ledgerlens.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The vital signs of one batch. Every field is a ratio or an average computed from stored rows, so
 * two batches of different sizes can be compared directly.
 *
 * @param failureRateByHour keyed by hour of day, "0".."23" — a gateway that breaks at 3am shows up
 *                          here and nowhere else, because it barely moves the overall rate
 */
public record BatchMetrics(
        BigDecimal feeRate,
        BigDecimal failureRate,
        Map<String, BigDecimal> failureRateByMethod,
        Map<String, BigDecimal> failureRateByHour,
        BigDecimal disputeRate,
        BigDecimal matchRate,
        Map<String, BigDecimal> settlementDelayDaysByMethod,
        BigDecimal avgSettlementDelayDays,
        int orderCount) {
}
