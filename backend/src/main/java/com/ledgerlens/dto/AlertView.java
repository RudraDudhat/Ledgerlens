package com.ledgerlens.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param metric        the metric that moved, or "failure_rate_hour_03" for an hour bucket
 * @param likelyCause   from the model, or null when none is configured
 * @param sourceRowIds  the rows driving it, capped at fifty
 */
public record AlertView(
        Long id,
        String metric,
        BigDecimal currentValue,
        BigDecimal baselineValue,
        BigDecimal ratio,
        String severity,
        List<Long> sourceRowIds,
        String likelyCause,
        String suggestedCheck,
        BigDecimal confidence) {
}
