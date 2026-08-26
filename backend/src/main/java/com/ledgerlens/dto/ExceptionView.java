package com.ledgerlens.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One thing the reconciler could not simply match, with why it thinks so and how sure it is.
 *
 * @param entityRef the order id for order-level findings, the UTR for bank-level ones
 * @param origin    RULE when a deterministic rule decided this, LLM when the classifier did
 */
public record ExceptionView(
        Long id,
        String status,
        String entityRef,
        String reason,
        BigDecimal confidence,
        BigDecimal amount,
        String method,
        String origin,
        List<Long> sourceRowIds) {
}
