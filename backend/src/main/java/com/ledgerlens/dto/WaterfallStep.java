package com.ledgerlens.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One signed step between gross sales and money in the bank. Every step is a delta, never a
 * subtotal, so the steps add up to the bank credits exactly once.
 */
public record WaterfallStep(String label, BigDecimal amount, List<Long> sourceRowIds) {
}
