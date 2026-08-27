package com.ledgerlens;

import com.ledgerlens.dto.BatchMetrics;
import com.ledgerlens.service.BatchHealthService;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Four orders with hand-computed ratios. A metric that is out by a rounding step is worse than no
 * metric at all, because every alert downstream is a comparison of two of these.
 *
 * <p>The fixture: two payments settle, one fails, one is held behind a dispute. Two were taken at
 * 03:00 and two at 10:00, so the hour buckets have something to separate.
 */
@Testcontainers
@SpringBootTest
class BatchHealthServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String ORDERS = """
            order_id,order_ts,amount,payment_id,method,payment_status,dispute_status,dispute_opened_at
            ORD-H1,2026-07-27T10:00:00,1000.00,pay_H1,UPI,CAPTURED,,
            ORD-H2,2026-07-27T03:00:00,1000.00,pay_H2,CARD,CAPTURED,,
            ORD-H3,2026-07-27T03:30:00,1000.00,pay_H3,UPI,FAILED,,
            ORD-H4,2026-07-27T10:30:00,1000.00,pay_H4,UPI,CAPTURED,OPEN,2026-07-28T09:00:00
            """;
    private static final String SETTLEMENT = """
            utr,settled_on,entity_type,entity_id,order_id,method,gross_amount,fee,gst,net_amount
            UTRHEALTH0001,2026-07-28,payment,pay_H1,ORD-H1,UPI,1000.00,0.00,0.00,1000.00
            UTRHEALTH0002,2026-07-29,payment,pay_H2,ORD-H2,CARD,1000.00,20.00,3.60,976.40
            """;
    private static final String BANK = """
            entry_date,description,utr,credit_amount
            2026-07-28,NEFT/UTRHEALTH0001/RAZORPAY,UTRHEALTH0001,1000.00
            2026-07-29,NEFT/UTRHEALTH0002/RAZORPAY,UTRHEALTH0002,976.40
            """;

    @Autowired
    CsvIngestService ingestService;
    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    BatchHealthService batchHealthService;

    @Test
    void feeRateIsFeesOverSales() throws IOException {
        // Only the card was charged: 20.00 of fees against 4000.00 of sales.
        assertThat(metrics().feeRate()).isEqualByComparingTo("0.005000");
    }

    @Test
    void failureRateCountsAttemptsNotSettlements() throws IOException {
        // One of four payments failed, and the held one still counts as an attempt that succeeded.
        assertThat(metrics().failureRate()).isEqualByComparingTo("0.250000");
    }

    @Test
    void failureRateIsBrokenDownByMethod() throws IOException {
        BatchMetrics metrics = metrics();

        // One of three UPI attempts failed; the single card attempt did not.
        assertThat(metrics.failureRateByMethod().get("UPI")).isEqualByComparingTo("0.333333");
        assertThat(metrics.failureRateByMethod().get("CARD")).isEqualByComparingTo("0.000000");
    }

    @Test
    void everyHourIsReportedSoAQuietHourReadsAsZeroRatherThanAsAGap() throws IOException {
        BatchMetrics metrics = metrics();

        assertThat(metrics.failureRateByHour()).hasSize(24);
        assertThat(metrics.failureRateByHour().get("3")).as("one of two 03:00 attempts failed")
                .isEqualByComparingTo("0.500000");
        assertThat(metrics.failureRateByHour().get("10")).isEqualByComparingTo("0.000000");
        assertThat(metrics.failureRateByHour().get("17")).as("no trade at all").isEqualByComparingTo("0.000000");
    }

    @Test
    void disputeRateIsDisputesOverPayments() throws IOException {
        assertThat(metrics().disputeRate()).isEqualByComparingTo("0.250000");
    }

    @Test
    void matchRateIsOrdersPairedWithASettlementLine() throws IOException {
        // The failed and the held payment never settle, so two of four match.
        assertThat(metrics().matchRate()).isEqualByComparingTo("0.500000");
    }

    @Test
    void settlementDelayIsCountedInBusinessDaysToTheBankCredit() throws IOException {
        BatchMetrics metrics = metrics();

        // Monday to Tuesday is one business day; Monday to Wednesday is two.
        assertThat(metrics.settlementDelayDaysByMethod().get("UPI")).isEqualByComparingTo("1.000000");
        assertThat(metrics.settlementDelayDaysByMethod().get("CARD")).isEqualByComparingTo("2.000000");
        assertThat(metrics.avgSettlementDelayDays()).isEqualByComparingTo("1.500000");
    }

    @Test
    void weekendsDoNotCountTowardsASettlementDelay() {
        // Friday 2026-07-31 to Monday 2026-08-03 is one business day, not three calendar days.
        assertThat(BatchHealthService.businessDaysBetween(
                java.time.LocalDate.of(2026, 7, 31), java.time.LocalDate.of(2026, 8, 3))).isEqualTo(1);
    }

    @Test
    void theMetricsSurviveBeingStoredAndReadBack() throws IOException {
        UUID batchId = reconciledBatch();
        BatchMetrics computed = batchHealthService.computeAndStore(batchId);

        BatchMetrics restored = batchHealthService.read(batchHealthService.write(computed));

        assertThat(restored.feeRate()).isEqualByComparingTo(computed.feeRate());
        assertThat(restored.failureRateByHour()).hasSize(24);
        assertThat(restored.orderCount()).isEqualTo(4);
    }

    private BatchMetrics metrics() throws IOException {
        return batchHealthService.compute(reconciledBatch());
    }

    private UUID reconciledBatch() throws IOException {
        UUID batchId = ingestService.ingest(stream(ORDERS), stream(SETTLEMENT), stream(BANK)).batchId();
        reconciliationService.reconcile(batchId);
        return batchId;
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
