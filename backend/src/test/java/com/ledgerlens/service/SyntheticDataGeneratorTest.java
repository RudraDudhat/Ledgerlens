package com.ledgerlens.service;

import com.ledgerlens.dto.AnswerKey;
import com.ledgerlens.entity.ExceptionStatus;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SyntheticDataGeneratorTest {

    private static final int COUNT = 300;
    private static final long SEED = 42L;
    private static final double TOLERANCE = 0.01;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @TempDir
    Path tmp;

    private Path outDir;
    private AnswerKey key;
    private List<CSVRecord> orders;
    private List<CSVRecord> settlement;
    private List<CSVRecord> bank;

    @BeforeEach
    void generateBatch() throws IOException {
        outDir = tmp.resolve("first");
        new SyntheticDataWriter().write(outDir, new SyntheticDataGenerator().generate(COUNT, SEED));
        key = SyntheticDataWriter.answerKeyMapper()
                .readValue(outDir.resolve(SyntheticDataWriter.ANSWER_KEY_FILE).toFile(), AnswerKey.class);
        orders = parse(outDir.resolve(SyntheticDataWriter.ORDERS_FILE));
        settlement = parse(outDir.resolve(SyntheticDataWriter.SETTLEMENT_FILE));
        bank = parse(outDir.resolve(SyntheticDataWriter.BANK_FILE));
    }

    @Test
    void writesFourFilesThatParse() {
        assertThat(outDir.resolve(SyntheticDataWriter.ORDERS_FILE)).exists();
        assertThat(outDir.resolve(SyntheticDataWriter.SETTLEMENT_FILE)).exists();
        assertThat(outDir.resolve(SyntheticDataWriter.BANK_FILE)).exists();
        assertThat(outDir.resolve(SyntheticDataWriter.ANSWER_KEY_FILE)).exists();

        assertThat(orders).hasSize(COUNT);
        assertThat(settlement).isNotEmpty();
        assertThat(bank).isNotEmpty();
        assertThat(orders.get(0).toMap()).containsKeys("order_id", "order_ts", "amount", "payment_id",
                "method", "payment_status", "dispute_status", "dispute_opened_at");
        assertThat(settlement.get(0).toMap()).containsKeys("utr", "settled_on", "entity_type", "entity_id",
                "order_id", "method", "gross_amount", "fee", "gst", "net_amount");
        assertThat(bank.get(0).toMap()).containsKeys("entry_date", "description", "utr", "credit_amount");
    }

    @Test
    void everyMoneyColumnIsWrittenInPaise() {
        for (CSVRecord order : orders) {
            assertThat(new BigDecimal(order.get("amount")).scale()).isEqualTo(2);
        }
        for (CSVRecord line : settlement) {
            assertThat(new BigDecimal(line.get("net_amount")).scale()).isEqualTo(2);
            assertThat(new BigDecimal(line.get("fee")).scale()).isEqualTo(2);
            assertThat(new BigDecimal(line.get("gst")).scale()).isEqualTo(2);
        }
        for (CSVRecord credit : bank) {
            assertThat(new BigDecimal(credit.get("credit_amount")).scale()).isEqualTo(2);
        }
    }

    @Test
    void anomalyCountsMatchTheInjectedPercentages() {
        assertShare(count(ExceptionStatus.PAYMENT_FAILED), 0.05);
        assertShare(count(ExceptionStatus.HELD_DISPUTE), 0.03);
        assertShare(count(ExceptionStatus.BANK_DUPLICATE), 0.01);
        assertShare(count(ExceptionStatus.BANK_MISSING), 0.01);
        assertShare(count(ExceptionStatus.AMOUNT_MISMATCH), 0.01);

        long inWindowRefunds = key.anomalies().stream()
                .filter(a -> a.type() == ExceptionStatus.REFUND_PRIOR_CYCLE && !a.preWindow())
                .count();
        long preWindowRefunds = key.anomalies().stream()
                .filter(a -> a.type() == ExceptionStatus.REFUND_PRIOR_CYCLE && a.preWindow())
                .count();
        assertShare(inWindowRefunds, 0.04);
        assertThat(preWindowRefunds).isEqualTo(5);
    }

    @Test
    void failedPaymentsNeverReachSettlement() {
        Map<String, CSVRecord> ordersById = ordersById();
        for (AnswerKey.Anomaly anomaly : anomalies(ExceptionStatus.PAYMENT_FAILED)) {
            CSVRecord order = ordersById.get(anomaly.orderId());
            assertThat(order).as("order %s", anomaly.orderId()).isNotNull();
            assertThat(order.get("payment_status")).isEqualTo("FAILED");
            assertThat(settlementLinesFor(anomaly.orderId())).isEmpty();
        }
    }

    @Test
    void disputedPaymentsAreHeldOutOfSettlementAndReleaseAfterTheBatch() {
        Map<String, CSVRecord> ordersById = ordersById();
        for (AnswerKey.Anomaly anomaly : anomalies(ExceptionStatus.HELD_DISPUTE)) {
            CSVRecord order = ordersById.get(anomaly.orderId());
            assertThat(order).as("order %s", anomaly.orderId()).isNotNull();
            assertThat(order.get("payment_status")).isEqualTo("CAPTURED");
            assertThat(order.get("dispute_status")).isNotBlank();
            assertThat(order.get("dispute_opened_at")).isNotBlank();
            assertThat(settlementLinesFor(anomaly.orderId())).isEmpty();
            if (anomaly.expectedSettlementDate() != null) {
                assertThat(anomaly.expectedSettlementDate()).isAfter(key.settlementCutoff());
            }
        }
    }

    @Test
    void refundsAreDeductedFromALaterCycleThanTheirPayment() {
        for (AnswerKey.Anomaly anomaly : anomalies(ExceptionStatus.REFUND_PRIOR_CYCLE)) {
            List<CSVRecord> lines = settlementLinesFor(anomaly.orderId());
            List<CSVRecord> refundLines = lines.stream().filter(l -> l.get("entity_type").equals("refund")).toList();
            assertThat(refundLines).as("refund line for %s", anomaly.orderId()).hasSize(1);
            assertThat(new BigDecimal(refundLines.get(0).get("net_amount")).negate())
                    .isEqualByComparingTo(anomaly.expectedAmount());

            List<CSVRecord> paymentLines = lines.stream().filter(l -> l.get("entity_type").equals("payment")).toList();
            if (anomaly.preWindow()) {
                assertThat(paymentLines).isEmpty();
                assertThat(ordersById()).doesNotContainKey(anomaly.orderId());
            } else {
                assertThat(paymentLines).hasSize(1);
                assertThat(refundLines.get(0).get("settled_on"))
                        .isGreaterThan(paymentLines.get(0).get("settled_on"));
            }
        }
    }

    @Test
    void bankAnomaliesLookTheWayTheAnswerKeySaysTheyDo() {
        Map<String, BigDecimal> settledByUtr = settledByUtr();

        for (AnswerKey.Anomaly anomaly : anomalies(ExceptionStatus.BANK_MISSING)) {
            assertThat(settledByUtr).containsKey(anomaly.utr());
            assertThat(bankRowsFor(anomaly.utr())).isEmpty();
        }
        for (AnswerKey.Anomaly anomaly : anomalies(ExceptionStatus.BANK_DUPLICATE)) {
            List<CSVRecord> rows = bankRowsFor(anomaly.utr());
            assertThat(rows).hasSize(2);
            assertThat(new BigDecimal(rows.get(0).get("credit_amount")))
                    .isEqualByComparingTo(new BigDecimal(rows.get(1).get("credit_amount")))
                    .isEqualByComparingTo(settledByUtr.get(anomaly.utr()));
        }
        for (AnswerKey.Anomaly anomaly : anomalies(ExceptionStatus.AMOUNT_MISMATCH)) {
            List<CSVRecord> rows = bankRowsFor(anomaly.utr());
            assertThat(rows).hasSize(1);
            BigDecimal credited = new BigDecimal(rows.get(0).get("credit_amount"));
            BigDecimal off = credited.subtract(settledByUtr.get(anomaly.utr())).abs();
            assertThat(off).isBetween(new BigDecimal("1.00"), new BigDecimal("50.00"));
            assertThat(credited).isEqualByComparingTo(anomaly.observedAmount());
        }
    }

    @Test
    void everyCleanOrderSettlesExactlyOnce() {
        Set<String> flagged = new HashSet<>();
        anomalies(ExceptionStatus.PAYMENT_FAILED).forEach(a -> flagged.add(a.orderId()));
        anomalies(ExceptionStatus.HELD_DISPUTE).forEach(a -> flagged.add(a.orderId()));

        int settledCleanly = 0;
        for (CSVRecord order : orders) {
            String orderId = order.get("order_id");
            if (flagged.contains(orderId)) {
                continue;
            }
            List<CSVRecord> paymentLines = settlementLinesFor(orderId).stream()
                    .filter(l -> l.get("entity_type").equals("payment")).toList();
            assertThat(paymentLines).as("payment line for %s", orderId).hasSize(1);
            settledCleanly++;
        }
        assertThat(settledCleanly).isEqualTo(COUNT - flagged.size());
    }

    @Test
    void theWaterfallSumsExactlyToTheBankCredits() {
        AnswerKey.Totals totals = key.totals();

        BigDecimal settled = totals.grossSales()
                .subtract(totals.failedAmount())
                .subtract(totals.totalFees())
                .subtract(totals.totalGst())
                .subtract(totals.heldNet())
                .subtract(totals.refundsTotal());
        assertThat(settled).isEqualByComparingTo(totals.totalSettled());

        BigDecimal credits = totals.totalSettled()
                .subtract(totals.bankMissingTotal())
                .add(totals.bankDuplicateTotal())
                .add(totals.bankMismatchDelta());
        assertThat(credits).isEqualByComparingTo(totals.totalBankCredits());

        assertThat(sum(settlement, "net_amount")).isEqualByComparingTo(totals.totalSettled());
        assertThat(sum(bank, "credit_amount")).isEqualByComparingTo(totals.totalBankCredits());
        assertThat(sum(orders, "amount")).isEqualByComparingTo(totals.grossSales());
    }

    @Test
    void theSameSeedProducesByteIdenticalFiles() throws IOException {
        Path second = tmp.resolve("second");
        new SyntheticDataWriter().write(second, new SyntheticDataGenerator().generate(COUNT, SEED));

        for (String file : List.of(SyntheticDataWriter.ORDERS_FILE, SyntheticDataWriter.SETTLEMENT_FILE,
                SyntheticDataWriter.BANK_FILE, SyntheticDataWriter.ANSWER_KEY_FILE)) {
            assertThat(Files.readAllBytes(second.resolve(file)))
                    .as(file)
                    .isEqualTo(Files.readAllBytes(outDir.resolve(file)));
        }
    }

    @Test
    void aDifferentSeedProducesDifferentOrders() throws IOException {
        Path other = tmp.resolve("other");
        new SyntheticDataWriter().write(other, new SyntheticDataGenerator().generate(COUNT, SEED + 1));

        assertThat(Files.readAllBytes(other.resolve(SyntheticDataWriter.ORDERS_FILE)))
                .isNotEqualTo(Files.readAllBytes(outDir.resolve(SyntheticDataWriter.ORDERS_FILE)));
    }

    @Test
    void tinyBatchesAreRejectedRatherThanSilentlySkewed() {
        assertThatThrownBy(() -> new SyntheticDataGenerator().generate(10, SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");
    }

    // ---------- helpers ----------

    private void assertShare(long actual, double expected) {
        assertThat((double) actual / COUNT).isCloseTo(expected, within(TOLERANCE));
    }

    private long count(ExceptionStatus type) {
        return anomalies(type).size();
    }

    private List<AnswerKey.Anomaly> anomalies(ExceptionStatus type) {
        return key.anomalies().stream().filter(a -> a.type() == type).toList();
    }

    private Map<String, CSVRecord> ordersById() {
        Map<String, CSVRecord> byId = new HashMap<>();
        orders.forEach(order -> byId.put(order.get("order_id"), order));
        return byId;
    }

    private List<CSVRecord> settlementLinesFor(String orderId) {
        return settlement.stream().filter(line -> line.get("order_id").equals(orderId)).toList();
    }

    private List<CSVRecord> bankRowsFor(String utr) {
        return bank.stream().filter(row -> row.get("utr").equals(utr)).toList();
    }

    private Map<String, BigDecimal> settledByUtr() {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (CSVRecord line : settlement) {
            totals.merge(line.get("utr"), new BigDecimal(line.get("net_amount")), BigDecimal::add);
        }
        return totals;
    }

    private static BigDecimal sum(List<CSVRecord> records, String column) {
        BigDecimal total = ZERO;
        for (CSVRecord record : records) {
            total = total.add(new BigDecimal(record.get(column)));
        }
        return total;
    }

    private static List<CSVRecord> parse(Path path) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {
            return new ArrayList<>(parser.getRecords());
        }
    }
}
