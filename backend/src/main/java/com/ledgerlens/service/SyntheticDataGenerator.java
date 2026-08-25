package com.ledgerlens.service;

import com.ledgerlens.entity.DisputeStatus;
import com.ledgerlens.entity.ExceptionStatus;
import com.ledgerlens.entity.PaymentMethod;
import com.ledgerlens.entity.PaymentStatus;
import com.ledgerlens.dto.AnswerKey;
import com.ledgerlens.dto.SyntheticDataset;
import com.ledgerlens.dto.SyntheticDataset.BankRow;
import com.ledgerlens.dto.SyntheticDataset.OrderRow;
import com.ledgerlens.dto.SyntheticDataset.SettlementRow;
import com.ledgerlens.rules.FeeSchedule;
import com.ledgerlens.rules.SettlementCalendar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a reproducible three-file batch with a known set of injected anomalies.
 *
 * <p>Two constraints keep the generated anomalies unambiguous. Disputes are only opened on orders
 * from the last {@link #DISPUTE_WINDOW_DAYS} days, so every disputed payment is still held at the
 * settlement cutoff and its expected release date lands after the batch. Refunded orders are only
 * taken from the first {@link #REFUND_MAX_DAY_OFFSET} days, so each refund is deducted from a later
 * cycle than the one its payment settled in, and still inside the batch.
 */
public class SyntheticDataGenerator {

    public static final LocalDate WINDOW_START = LocalDate.of(2026, 7, 27);
    public static final int WINDOW_DAYS = 31;

    private static final int MIN_COUNT = 50;
    private static final int DISPUTE_WINDOW_DAYS = 10;
    private static final int REFUND_MAX_DAY_OFFSET = 20;
    private static final int PRE_WINDOW_REFUNDS = 5;

    private static final double PCT_PAYMENT_FAILED = 0.05;
    private static final double PCT_HELD_DISPUTE = 0.03;
    private static final double PCT_REFUND_PRIOR_CYCLE = 0.04;
    private static final double PCT_BANK_DUPLICATE = 0.01;
    private static final double PCT_BANK_MISSING = 0.01;
    private static final double PCT_AMOUNT_MISMATCH = 0.01;

    private static final int DISPUTE_RELEASE_DAYS = 14;
    private static final DisputeStatus[] DISPUTE_CYCLE = {DisputeStatus.WON, DisputeStatus.OPEN, DisputeStatus.LOST};
    private static final int[] PAISE_OPTIONS = {0, 0, 0, 25, 50, 75, 99};
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    private static final DateTimeFormatter UTR_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    public SyntheticDataset generate(int count, long seed) {
        if (count < MIN_COUNT) {
            throw new IllegalArgumentException("count must be at least " + MIN_COUNT + " but was " + count);
        }
        Random rnd = new Random(seed);

        List<Draft> drafts = draftOrders(count, rnd);
        Selection selection = selectOrderAnomalies(count, drafts, rnd);
        List<OrderRow> orders = buildOrders(drafts, selection);
        List<RefundDraft> refunds = buildRefunds(orders, selection, rnd);
        List<Batch> batches = buildBatches(orders, refunds);
        BankPlan bankPlan = planBankAnomalies(count, batches, rnd);
        List<BankRow> bankRows = buildBankRows(batches, bankPlan);
        AnswerKey answerKey = buildAnswerKey(count, seed, orders, refunds, batches, bankPlan);

        List<SettlementRow> settlementRows = new ArrayList<>();
        batches.forEach(batch -> settlementRows.addAll(batch.lines()));
        return new SyntheticDataset(orders, settlementRows, bankRows, answerKey);
    }

    // ---------- orders ----------

    private List<Draft> draftOrders(int count, Random rnd) {
        List<Draft> drafts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LocalDateTime ts = WINDOW_START.plusDays(rnd.nextInt(WINDOW_DAYS))
                    .atTime(8 + rnd.nextInt(13), rnd.nextInt(60), rnd.nextInt(60));
            drafts.add(new Draft(ts, randomAmount(rnd), randomMethod(rnd)));
        }
        drafts.sort(Comparator.comparing(Draft::ts));
        return drafts;
    }

    private Selection selectOrderAnomalies(int count, List<Draft> drafts, Random rnd) {
        int heldCount = share(count, PCT_HELD_DISPUTE);
        int refundCount = share(count, PCT_REFUND_PRIOR_CYCLE);
        int failedCount = share(count, PCT_PAYMENT_FAILED);

        LocalDate disputeFrom = WINDOW_START.plusDays(WINDOW_DAYS - DISPUTE_WINDOW_DAYS);
        List<Integer> lateIndexes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!drafts.get(i).ts().toLocalDate().isBefore(disputeFrom)) {
                lateIndexes.add(i);
            }
        }
        require(lateIndexes.size() >= heldCount, "not enough late orders to open " + heldCount + " disputes");
        Collections.shuffle(lateIndexes, rnd);
        List<Integer> held = List.copyOf(lateIndexes.subList(0, heldCount));

        LocalDate refundCutoff = WINDOW_START.plusDays(REFUND_MAX_DAY_OFFSET);
        List<Integer> earlyIndexes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!held.contains(i) && !drafts.get(i).ts().toLocalDate().isAfter(refundCutoff)) {
                earlyIndexes.add(i);
            }
        }
        require(earlyIndexes.size() >= refundCount, "not enough early orders to refund " + refundCount);
        Collections.shuffle(earlyIndexes, rnd);
        List<Integer> refunded = List.copyOf(earlyIndexes.subList(0, refundCount));

        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!held.contains(i) && !refunded.contains(i)) {
                remaining.add(i);
            }
        }
        require(remaining.size() >= failedCount, "not enough orders left to fail " + failedCount);
        Collections.shuffle(remaining, rnd);
        Set<Integer> failed = new LinkedHashSet<>(remaining.subList(0, failedCount));

        return new Selection(failed, held, refunded);
    }

    private List<OrderRow> buildOrders(List<Draft> drafts, Selection selection) {
        List<OrderRow> orders = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            int heldPosition = selection.held().indexOf(i);
            DisputeStatus disputeStatus = heldPosition < 0 ? null : DISPUTE_CYCLE[heldPosition % DISPUTE_CYCLE.length];
            LocalDateTime disputeOpenedAt = heldPosition < 0 ? null : draft.ts().plusDays(1);
            orders.add(new OrderRow(
                    String.format("ORD-%06d", i + 1),
                    draft.ts(),
                    draft.amount(),
                    String.format("pay_SYN%011d", i + 1),
                    draft.method(),
                    selection.failed().contains(i) ? PaymentStatus.FAILED : PaymentStatus.CAPTURED,
                    disputeStatus,
                    disputeOpenedAt));
        }
        return orders;
    }

    // ---------- refunds ----------

    private List<RefundDraft> buildRefunds(List<OrderRow> orders, Selection selection, Random rnd) {
        List<RefundDraft> refunds = new ArrayList<>();
        int sequence = 1;
        for (int index : selection.refunded()) {
            OrderRow order = orders.get(index);
            LocalDate settledOn = SettlementCalendar.settlementDate(order.method(), order.orderTs().toLocalDate());
            LocalDate createdOn = settledOn.plusDays(2 + rnd.nextInt(5));
            BigDecimal amount = rnd.nextInt(100) < 60 ? order.amount() : partOf(order.amount(), 40 + rnd.nextInt(41));
            refunds.add(new RefundDraft(
                    String.format("rfnd_SYN%010d", sequence++),
                    order.paymentId(), order.orderId(), order.method(), amount, createdOn, false));
        }
        for (int i = 0; i < PRE_WINDOW_REFUNDS; i++) {
            refunds.add(new RefundDraft(
                    String.format("rfnd_SYNPRE%06d", i + 1),
                    String.format("pay_SYNPRE%08d", i + 1),
                    String.format("ORD-PRE-%04d", i + 1),
                    randomMethod(rnd),
                    randomAmount(rnd),
                    WINDOW_START.plusDays(3 + rnd.nextInt(18)),
                    true));
        }
        return refunds;
    }

    // ---------- settlement ----------

    private List<Batch> buildBatches(List<OrderRow> orders, List<RefundDraft> refunds) {
        TreeMap<LocalDate, List<SettlementRow>> byDate = new TreeMap<>();
        for (OrderRow order : orders) {
            if (order.paymentStatus() != PaymentStatus.CAPTURED || order.disputeStatus() != null) {
                continue;
            }
            LocalDate settledOn = SettlementCalendar.settlementDate(order.method(), order.orderTs().toLocalDate());
            BigDecimal fee = FeeSchedule.fee(order.method(), order.amount());
            BigDecimal gst = FeeSchedule.gst(fee);
            byDate.computeIfAbsent(settledOn, date -> new ArrayList<>()).add(new SettlementRow(
                    utrFor(settledOn), settledOn, "payment", order.paymentId(), order.orderId(), order.method(),
                    order.amount(), fee, gst, FeeSchedule.net(order.amount(), fee, gst)));
        }
        for (RefundDraft refund : refunds) {
            LocalDate settledOn = SettlementCalendar.refundSettlementDate(refund.createdOn());
            BigDecimal negated = refund.amount().negate();
            byDate.computeIfAbsent(settledOn, date -> new ArrayList<>()).add(new SettlementRow(
                    utrFor(settledOn), settledOn, "refund", refund.refundId(), refund.orderId(), refund.method(),
                    negated, ZERO, ZERO, negated));
        }

        List<Batch> batches = new ArrayList<>(byDate.size());
        byDate.forEach((date, lines) -> {
            lines.sort(Comparator.comparing(SettlementRow::entityType).thenComparing(SettlementRow::entityId));
            BigDecimal amount = lines.stream().map(SettlementRow::netAmount).reduce(ZERO, BigDecimal::add);
            require(amount.signum() > 0, "settlement batch on " + date + " is not a positive credit: " + amount);
            batches.add(new Batch(date, utrFor(date), amount, List.copyOf(lines)));
        });
        return batches;
    }

    // ---------- bank ----------

    private BankPlan planBankAnomalies(int count, List<Batch> batches, Random rnd) {
        int duplicateCount = share(count, PCT_BANK_DUPLICATE);
        int missingCount = share(count, PCT_BANK_MISSING);
        int mismatchCount = share(count, PCT_AMOUNT_MISMATCH);
        require(batches.size() >= duplicateCount + missingCount + mismatchCount,
                "not enough settlement batches to inject bank anomalies");

        List<Batch> shuffled = new ArrayList<>(batches);
        Collections.shuffle(shuffled, rnd);
        Set<String> duplicated = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        Map<String, BigDecimal> mismatchDeltas = new LinkedHashMap<>();

        int cursor = 0;
        for (int i = 0; i < duplicateCount; i++) {
            duplicated.add(shuffled.get(cursor++).utr());
        }
        for (int i = 0; i < missingCount; i++) {
            missing.add(shuffled.get(cursor++).utr());
        }
        for (int i = 0; i < mismatchCount; i++) {
            int rupees = 1 + rnd.nextInt(50);
            BigDecimal delta = BigDecimal.valueOf(rnd.nextBoolean() ? -rupees : rupees).setScale(2, RoundingMode.UNNECESSARY);
            mismatchDeltas.put(shuffled.get(cursor++).utr(), delta);
        }
        return new BankPlan(duplicated, missing, mismatchDeltas);
    }

    private List<BankRow> buildBankRows(List<Batch> batches, BankPlan plan) {
        List<BankRow> rows = new ArrayList<>();
        for (Batch batch : batches) {
            if (plan.missing().contains(batch.utr())) {
                continue;
            }
            BigDecimal credited = batch.amount().add(plan.mismatchDeltas().getOrDefault(batch.utr(), ZERO));
            BankRow row = new BankRow(batch.date(),
                    "NEFT/" + batch.utr() + "/RAZORPAY SOFTWARE PVT LTD", batch.utr(), credited);
            rows.add(row);
            if (plan.duplicated().contains(batch.utr())) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing(BankRow::entryDate).thenComparing(BankRow::utr));
        return rows;
    }

    // ---------- answer key ----------

    private AnswerKey buildAnswerKey(int count, long seed, List<OrderRow> orders, List<RefundDraft> refunds,
                                     List<Batch> batches, BankPlan plan) {
        List<AnswerKey.Anomaly> anomalies = new ArrayList<>();
        BigDecimal grossSales = ZERO;
        BigDecimal failedAmount = ZERO;
        BigDecimal totalFees = ZERO;
        BigDecimal totalGst = ZERO;
        BigDecimal heldNet = ZERO;
        Map<LocalDate, Map<PaymentMethod, BigDecimal>> futureByDate = new TreeMap<>();

        for (OrderRow order : orders) {
            grossSales = grossSales.add(order.amount());
            if (order.paymentStatus() == PaymentStatus.FAILED) {
                failedAmount = failedAmount.add(order.amount());
                anomalies.add(new AnswerKey.Anomaly(ExceptionStatus.PAYMENT_FAILED, order.orderId(), null,
                        order.amount(), null, null, false,
                        "payment failed at the gateway, so it never reached a settlement or the bank"));
                continue;
            }
            BigDecimal fee = FeeSchedule.fee(order.method(), order.amount());
            BigDecimal gst = FeeSchedule.gst(fee);
            BigDecimal net = FeeSchedule.net(order.amount(), fee, gst);
            totalFees = totalFees.add(fee);
            totalGst = totalGst.add(gst);
            if (order.disputeStatus() != null) {
                heldNet = heldNet.add(net);
                LocalDate release = order.disputeStatus() == DisputeStatus.WON
                        ? order.disputeOpenedAt().toLocalDate().plusDays(DISPUTE_RELEASE_DAYS)
                        : null;
                anomalies.add(new AnswerKey.Anomaly(ExceptionStatus.HELD_DISPUTE, order.orderId(), null,
                        net, null, release, false,
                        "dispute " + order.disputeStatus() + " opened " + order.disputeOpenedAt().toLocalDate()
                                + " holds this payment out of settlement"));
                if (release != null) {
                    futureByDate.computeIfAbsent(release, date -> new LinkedHashMap<>())
                            .merge(order.method(), net, BigDecimal::add);
                }
            }
        }

        BigDecimal refundsTotal = ZERO;
        for (RefundDraft refund : refunds) {
            refundsTotal = refundsTotal.add(refund.amount());
            LocalDate deductedOn = SettlementCalendar.refundSettlementDate(refund.createdOn());
            anomalies.add(new AnswerKey.Anomaly(ExceptionStatus.REFUND_PRIOR_CYCLE, refund.orderId(), null,
                    refund.amount(), null, deductedOn, refund.preWindow(),
                    refund.preWindow()
                            ? "refund of an order from before the batch window, so no order row exists to match it"
                            : "refund created after its payment settled, so it is deducted from the " + deductedOn
                                    + " cycle instead"));
        }

        BigDecimal totalSettled = ZERO;
        BigDecimal bankMissingTotal = ZERO;
        BigDecimal bankDuplicateTotal = ZERO;
        BigDecimal bankMismatchDelta = ZERO;
        for (Batch batch : batches) {
            totalSettled = totalSettled.add(batch.amount());
            if (plan.missing().contains(batch.utr())) {
                bankMissingTotal = bankMissingTotal.add(batch.amount());
                anomalies.add(new AnswerKey.Anomaly(ExceptionStatus.BANK_MISSING, null, batch.utr(),
                        batch.amount(), null, batch.date(), false,
                        "settlement of " + batch.amount() + " never appeared as a bank credit"));
            }
            if (plan.duplicated().contains(batch.utr())) {
                bankDuplicateTotal = bankDuplicateTotal.add(batch.amount());
                anomalies.add(new AnswerKey.Anomaly(ExceptionStatus.BANK_DUPLICATE, null, batch.utr(),
                        batch.amount(), batch.amount().multiply(BigDecimal.TWO), batch.date(), false,
                        "the bank posted this settlement credit twice"));
            }
            BigDecimal delta = plan.mismatchDeltas().get(batch.utr());
            if (delta != null) {
                bankMismatchDelta = bankMismatchDelta.add(delta);
                anomalies.add(new AnswerKey.Anomaly(ExceptionStatus.AMOUNT_MISMATCH, null, batch.utr(),
                        batch.amount(), batch.amount().add(delta), batch.date(), false,
                        "bank credit differs from the settlement total by " + delta));
            }
        }

        BigDecimal expectedSettled = grossSales.subtract(failedAmount).subtract(totalFees)
                .subtract(totalGst).subtract(heldNet).subtract(refundsTotal);
        require(expectedSettled.compareTo(totalSettled) == 0,
                "waterfall does not reconcile: expected " + expectedSettled + " but batches total " + totalSettled);
        BigDecimal totalBankCredits = totalSettled.subtract(bankMissingTotal)
                .add(bankDuplicateTotal).add(bankMismatchDelta);

        anomalies.sort(Comparator.comparing((AnswerKey.Anomaly a) -> a.type().name())
                .thenComparing(a -> a.orderId() != null ? a.orderId() : a.utr()));
        Map<String, Integer> counts = new TreeMap<>();
        anomalies.forEach(a -> counts.merge(a.type().name(), 1, Integer::sum));

        List<AnswerKey.FutureSettlement> future = new ArrayList<>();
        futureByDate.forEach((date, byMethod) -> {
            Map<String, BigDecimal> breakdown = new TreeMap<>();
            BigDecimal total = ZERO;
            for (Map.Entry<PaymentMethod, BigDecimal> entry : byMethod.entrySet()) {
                breakdown.put(entry.getKey().name(), entry.getValue());
                total = total.add(entry.getValue());
            }
            future.add(new AnswerKey.FutureSettlement(date, total, breakdown));
        });

        AnswerKey.Totals totals = new AnswerKey.Totals(grossSales, failedAmount, totalFees, totalGst, heldNet,
                refundsTotal, totalSettled, bankMissingTotal, bankDuplicateTotal, bankMismatchDelta, totalBankCredits);
        return new AnswerKey(seed, count, WINDOW_START, WINDOW_START.plusDays(WINDOW_DAYS - 1L),
                batches.get(batches.size() - 1).date(), totals, counts, List.copyOf(anomalies), List.copyOf(future));
    }

    // ---------- helpers ----------

    private static int share(int count, double percentage) {
        return (int) Math.round(count * percentage);
    }

    private static String utrFor(LocalDate date) {
        return "UTR" + date.format(UTR_DATE) + "01";
    }

    private static BigDecimal randomAmount(Random rnd) {
        int rupees = 149 + rnd.nextInt(9851);
        int paise = PAISE_OPTIONS[rnd.nextInt(PAISE_OPTIONS.length)];
        return BigDecimal.valueOf(rupees).add(BigDecimal.valueOf(paise, 2)).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal partOf(BigDecimal amount, int percentage) {
        return amount.multiply(BigDecimal.valueOf(percentage))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** Indian mix: UPI dominant, cards second, netbanking and wallets small. */
    private static PaymentMethod randomMethod(Random rnd) {
        int roll = rnd.nextInt(100);
        if (roll < 55) {
            return PaymentMethod.UPI;
        }
        if (roll < 80) {
            return PaymentMethod.CARD;
        }
        if (roll < 90) {
            return PaymentMethod.NETBANKING;
        }
        return PaymentMethod.WALLET;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Draft(LocalDateTime ts, BigDecimal amount, PaymentMethod method) {
    }

    private record Selection(Set<Integer> failed, List<Integer> held, List<Integer> refunded) {
    }

    private record RefundDraft(String refundId, String paymentId, String orderId, PaymentMethod method,
                               BigDecimal amount, LocalDate createdOn, boolean preWindow) {
    }

    private record Batch(LocalDate date, String utr, BigDecimal amount, List<SettlementRow> lines) {
    }

    private record BankPlan(Set<String> duplicated, Set<String> missing, Map<String, BigDecimal> mismatchDeltas) {
    }
}
