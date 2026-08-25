package com.ledgerlens.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Headline numbers for a batch. The match rate is the share of orders whose payment was found in the
 * settlement report; failed and held payments are legitimately absent and so drag it below 100%.
 */
public record ReconcileSummary(
        UUID batchId,
        int orderCount,
        int matchedOrderCount,
        BigDecimal orderMatchRate,
        int settlementBatchCount,
        int matchedSettlementBatchCount,
        int bankEntryCount,
        int matchedBankEntryCount,
        Map<String, Integer> matchesByType,
        BigDecimal grossSales,
        BigDecimal totalSettled,
        BigDecimal totalBankCredits) {
}
