package com.ledgerlens.service;

import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.entity.MatchRecord;
import com.ledgerlens.entity.MerchantOrder;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.entity.SettlementLine;
import com.ledgerlens.entity.SettlementLineType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The matcher touches no database, so its rules can be checked directly against hand-built rows. */
class DeterministicMatcherTest {

    private static final UUID BATCH = UUID.randomUUID();
    private static final LocalDate SETTLED_ON = LocalDate.of(2026, 7, 28);

    private final DeterministicMatcher matcher = new DeterministicMatcher();

    @Test
    void ordersMatchTheirSettlementLineOnIdAndGrossAmount() {
        MerchantOrder order = order(1L, "ORD-1", "1000.00");
        SettlementLine line = paymentLine(10L, "ORD-1", "1000.00", 100L);

        List<MatchRecord> matches = matcher.match(BATCH, List.of(order), List.of(line), List.of(), List.of());

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getMatchType()).isEqualTo(DeterministicMatcher.ORDER_ID_AMOUNT_EXACT);
        assertThat(matches.get(0).getOrderRowId()).isEqualTo(1L);
        assertThat(matches.get(0).getSettlementLineRowId()).isEqualTo(10L);
    }

    @Test
    void anOrderWhoseAmountDisagreesIsLeftUnmatched() {
        MerchantOrder order = order(1L, "ORD-1", "1000.00");
        SettlementLine line = paymentLine(10L, "ORD-1", "999.00", 100L);

        assertThat(matcher.match(BATCH, List.of(order), List.of(line), List.of(), List.of())).isEmpty();
    }

    @Test
    void refundLinesAreNeverMistakenForAPayment() {
        MerchantOrder order = order(1L, "ORD-1", "1000.00");
        SettlementLine refund = new SettlementLine();
        refund.setId(10L);
        refund.setBatchId(BATCH);
        refund.setSettlementBatchRowId(100L);
        refund.setLineType(SettlementLineType.REFUND);
        refund.setEntityId("rfnd_1");
        refund.setOrderId("ORD-1");
        refund.setAmount(new BigDecimal("-1000.00"));

        assertThat(matcher.match(BATCH, List.of(order), List.of(refund), List.of(), List.of())).isEmpty();
    }

    @Test
    void settlementsMatchTheBankOnUtrEvenWhenTheAmountIsShort() {
        SettlementBatch settlement = settlement(100L, "UTR2026072801", "5000.00", SETTLED_ON);
        BankEntry entry = bankEntry(200L, "UTR2026072801", "4951.00", SETTLED_ON, "NEFT/UTR2026072801/RAZORPAY");

        List<MatchRecord> matches = matcher.match(BATCH, List.of(), List.of(), List.of(settlement), List.of(entry));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getMatchType()).isEqualTo(DeterministicMatcher.UTR_EXACT);
        assertThat(matches.get(0).getBankEntryRowId()).isEqualTo(200L);
    }

    @Test
    void aUtrIsRecoveredFromTheNarrationWhenTheStatementHasNoUtrColumn() {
        SettlementBatch settlement = settlement(100L, "UTR2026072801", "5000.00", SETTLED_ON);
        BankEntry entry = bankEntry(200L, null, "5000.00", SETTLED_ON,
                "NEFT/UTR2026072801/RAZORPAY SOFTWARE PVT LTD");

        List<MatchRecord> matches = matcher.match(BATCH, List.of(), List.of(), List.of(settlement), List.of(entry));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getMatchType()).isEqualTo(DeterministicMatcher.UTR_EXACT);
    }

    /**
     * A declared UTR is normalised hard, but free text is read conservatively: gluing tokens back
     * together across separators would let unrelated narration words form a reference and produce a
     * confident wrong match, so a broken reference falls through to the amount and date check instead.
     */
    @Test
    void aReferenceBrokenAcrossSeparatorsIsNotGluedBackTogether() {
        assertThat(DeterministicMatcher.normalizeUtr("utr-2026/072801")).isEqualTo("UTR2026072801");
        assertThat(DeterministicMatcher.extractUtr("NEFT/utr-2026 072801/RAZORPAY")).isNull();

        SettlementBatch settlement = settlement(100L, "UTR2026072801", "5000.00", SETTLED_ON);
        BankEntry entry = bankEntry(200L, null, "5000.00", SETTLED_ON, "NEFT/utr-2026 072801/RAZORPAY");

        List<MatchRecord> matches = matcher.match(BATCH, List.of(), List.of(), List.of(settlement), List.of(entry));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getMatchType()).isEqualTo(DeterministicMatcher.AMOUNT_DATE_WINDOW);
    }

    @Test
    void aBatchWithNoUsableUtrFallsBackToAmountWithinTheDateWindow() {
        SettlementBatch settlement = settlement(100L, "UTR2026072801", "5000.00", SETTLED_ON);
        BankEntry entry = bankEntry(200L, null, "5000.00", SETTLED_ON.plusDays(2), "COLLECTION CREDIT");

        List<MatchRecord> matches = matcher.match(BATCH, List.of(), List.of(), List.of(settlement), List.of(entry));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getMatchType()).isEqualTo(DeterministicMatcher.AMOUNT_DATE_WINDOW);
    }

    @Test
    void theFallbackRefusesCreditsThatLandTooLate() {
        SettlementBatch settlement = settlement(100L, "UTR2026072801", "5000.00", SETTLED_ON);
        BankEntry entry = bankEntry(200L, null, "5000.00", SETTLED_ON.plusDays(3), "COLLECTION CREDIT");

        assertThat(matcher.match(BATCH, List.of(), List.of(), List.of(settlement), List.of(entry))).isEmpty();
    }

    @Test
    void aSettlementCreditedTwiceLeavesTheSecondRowUnmatched() {
        SettlementBatch settlement = settlement(100L, "UTR2026072801", "5000.00", SETTLED_ON);
        BankEntry first = bankEntry(200L, "UTR2026072801", "5000.00", SETTLED_ON, "NEFT");
        BankEntry duplicate = bankEntry(201L, "UTR2026072801", "5000.00", SETTLED_ON, "NEFT");

        List<MatchRecord> matches =
                matcher.match(BATCH, List.of(), List.of(), List.of(settlement), List.of(first, duplicate));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getBankEntryRowId()).isEqualTo(200L);
    }

    @Test
    void bankWordsAreNotMistakenForAUtr() {
        assertThat(DeterministicMatcher.extractUtr("NEFT/RAZORPAY SOFTWARE PVT LTD")).isNull();
        assertThat(DeterministicMatcher.extractUtr("NEFT/UTR2026072801/RAZORPAY")).isEqualTo("UTR2026072801");
        assertThat(DeterministicMatcher.normalizeUtr(" utr-2026/072801 ")).isEqualTo("UTR2026072801");
        assertThat(DeterministicMatcher.normalizeUtr("///")).isNull();
    }

    private static MerchantOrder order(Long id, String orderId, String amount) {
        MerchantOrder order = new MerchantOrder();
        order.setId(id);
        order.setBatchId(BATCH);
        order.setOrderId(orderId);
        order.setOrderTs(LocalDateTime.of(2026, 7, 27, 10, 0));
        order.setAmount(new BigDecimal(amount));
        return order;
    }

    private static SettlementLine paymentLine(Long id, String orderId, String amount, Long settlementBatchRowId) {
        SettlementLine line = new SettlementLine();
        line.setId(id);
        line.setBatchId(BATCH);
        line.setSettlementBatchRowId(settlementBatchRowId);
        line.setLineType(SettlementLineType.PAYMENT);
        line.setEntityId("pay_" + orderId);
        line.setOrderId(orderId);
        line.setAmount(new BigDecimal(amount));
        return line;
    }

    private static SettlementBatch settlement(Long id, String utr, String amount, LocalDate settledOn) {
        SettlementBatch settlement = new SettlementBatch();
        settlement.setId(id);
        settlement.setBatchId(BATCH);
        settlement.setUtr(utr);
        settlement.setSettledOn(settledOn);
        settlement.setAmount(new BigDecimal(amount));
        return settlement;
    }

    private static BankEntry bankEntry(Long id, String utr, String amount, LocalDate date, String description) {
        BankEntry entry = new BankEntry();
        entry.setId(id);
        entry.setBatchId(BATCH);
        entry.setEntryDate(date);
        entry.setDescription(description);
        entry.setUtr(utr);
        entry.setAmount(new BigDecimal(amount));
        return entry;
    }
}
