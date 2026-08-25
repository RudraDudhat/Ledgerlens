package com.ledgerlens.generator;

import com.ledgerlens.domain.ExceptionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Ground truth for a generated batch: every anomaly that was deliberately injected, so tests and the
 * metrics endpoint can score the reconciler against it. Order-level anomalies carry an orderId;
 * bank-level anomalies carry a utr instead.
 */
public record AnswerKey(
        long seed,
        int count,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalDate settlementCutoff,
        Totals totals,
        Map<String, Integer> anomalyCounts,
        List<Anomaly> anomalies,
        List<FutureSettlement> expectedFutureSettlements) {

    /** Every step of the sales-to-bank waterfall, as the generator actually built it. */
    public record Totals(
            BigDecimal grossSales,
            BigDecimal failedAmount,
            BigDecimal totalFees,
            BigDecimal totalGst,
            BigDecimal heldNet,
            BigDecimal refundsTotal,
            BigDecimal totalSettled,
            BigDecimal bankMissingTotal,
            BigDecimal bankDuplicateTotal,
            BigDecimal bankMismatchDelta,
            BigDecimal totalBankCredits) {
    }

    /**
     * @param orderId                order this anomaly belongs to, null for bank-level anomalies
     * @param utr                    settlement the anomaly belongs to, null for order-level anomalies
     * @param expectedAmount         the correct amount
     * @param observedAmount         what the input files actually show, when it differs
     * @param expectedSettlementDate when held money is expected to be released
     * @param preWindow              true for refunds of orders that predate the batch window
     */
    public record Anomaly(
            ExceptionStatus type,
            String orderId,
            String utr,
            BigDecimal expectedAmount,
            BigDecimal observedAmount,
            LocalDate expectedSettlementDate,
            boolean preWindow,
            String detail) {
    }

    /** A settlement expected after the batch ends, so the forecast has something to be scored against. */
    public record FutureSettlement(
            LocalDate date,
            BigDecimal expectedAmount,
            Map<String, BigDecimal> breakdownByMethod) {
    }
}
