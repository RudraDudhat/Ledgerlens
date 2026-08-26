package com.ledgerlens.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * One day on the forward settlement calendar.
 *
 * @param expectedAmount     total expected to reach the bank that day
 * @param breakdownByMethod  that total split by payment method
 * @param heldAmount         the part of it coming out of a dispute hold rather than a normal cycle
 */
public record ForecastEntry(
        LocalDate date,
        BigDecimal expectedAmount,
        Map<String, BigDecimal> breakdownByMethod,
        BigDecimal heldAmount) {
}
