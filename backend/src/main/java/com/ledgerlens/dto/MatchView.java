package com.ledgerlens.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One matched row. Order-to-settlement matches fill the order fields, settlement-to-bank matches
 * fill the bank fields, so the unused half is null.
 */
public record MatchView(
        Long id,
        String matchType,
        String orderId,
        String utr,
        BigDecimal amount,
        LocalDate settledOn,
        LocalDate bankDate,
        BigDecimal bankAmount) {
}
