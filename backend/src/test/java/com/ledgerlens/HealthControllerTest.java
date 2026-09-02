package com.ledgerlens;

import com.ledgerlens.dto.AlertView;
import com.ledgerlens.dto.HealthReport;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.AnomalyDetector;
import com.ledgerlens.service.HealthService;
import com.ledgerlens.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives three batches through in order — two ordinary, then one that went wrong — because the whole
 * point of the monitor is that a batch is only judged against what came before it.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    CsvIngestService ingestService;
    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    HealthService healthService;
    @Autowired
    com.ledgerlens.service.BatchHealthService batchHealthService;
    @Autowired
    com.ledgerlens.service.AnomalyDetector anomalyDetector;
    @Autowired
    MockMvc mockMvc;

    /** Four payments, one dispute, no failures: the shape of an ordinary week. */
    private static String ordinaryOrders(String tag) {
        return """
                order_id,order_ts,amount,payment_id,method,payment_status,dispute_status,dispute_opened_at
                ORD-%1$s1,2026-07-27T10:00:00,1000.00,pay_%1$s1,UPI,CAPTURED,,
                ORD-%1$s2,2026-07-27T11:00:00,1000.00,pay_%1$s2,UPI,CAPTURED,,
                ORD-%1$s3,2026-07-27T12:00:00,1000.00,pay_%1$s3,UPI,CAPTURED,,
                ORD-%1$s4,2026-07-27T13:00:00,1000.00,pay_%1$s4,UPI,CAPTURED,OPEN,2026-07-28T09:00:00
                """.formatted(tag);
    }

    /** Three disputes instead of one, and the only failure alone at 03:00. */
    private static String degradedOrders(String tag) {
        return """
                order_id,order_ts,amount,payment_id,method,payment_status,dispute_status,dispute_opened_at
                ORD-%1$s1,2026-07-27T03:00:00,1000.00,pay_%1$s1,UPI,FAILED,,
                ORD-%1$s2,2026-07-27T12:30:00,1000.00,pay_%1$s2,UPI,CAPTURED,OPEN,2026-07-28T09:00:00
                ORD-%1$s3,2026-07-27T12:00:00,1000.00,pay_%1$s3,UPI,CAPTURED,OPEN,2026-07-28T09:00:00
                ORD-%1$s4,2026-07-27T13:00:00,1000.00,pay_%1$s4,UPI,CAPTURED,OPEN,2026-07-28T09:00:00
                """.formatted(tag);
    }

    private static String settlement(String tag, int payments) {
        StringBuilder csv = new StringBuilder(
                "utr,settled_on,entity_type,entity_id,order_id,method,gross_amount,fee,gst,net_amount\n");
        for (int i = 1; i <= payments; i++) {
            csv.append("UTR%s0001,2026-07-28,payment,pay_%s%d,ORD-%s%d,UPI,1000.00,0.00,0.00,1000.00%n"
                    .formatted(tag, tag, i, tag, i).replace("%n", "\n"));
        }
        return csv.toString();
    }

    private static String bank(String tag, int payments) {
        return """
                entry_date,description,utr,credit_amount
                2026-07-28,NEFT/UTR%s0001/RAZORPAY,UTR%s0001,%d.00
                """.formatted(tag, tag, payments * 1000);
    }

    @Test
    void withoutTwoEarlierBatchesNothingIsAlerted() throws IOException {
        UUID batchId = reconcile(degradedOrders("A"), settlement("A", 3), bank("A", 3));
        var metrics = batchHealthService.compute(batchId);

        // Asserted against the detector directly: every test here shares one database, so "the first
        // batch ever" is not something a single test can arrange for itself.
        assertThat(anomalyDetector.detect(batchId, metrics, List.of())).isEmpty();
        assertThat(anomalyDetector.detect(batchId, metrics, List.of(metrics))).isEmpty();
        assertThat(AnomalyDetector.MIN_PRIOR_BATCHES).isEqualTo(2);
    }

    @Test
    void aBaselineIsOnlyOfferedOnceThereIsEnoughToBuildOneFrom() throws IOException {
        UUID batchId = reconcile(ordinaryOrders("N"), settlement("N", 3), bank("N", 3));

        HealthReport report = healthService.report(batchId);

        assertThat(report.insufficientHistory()).isEqualTo(report.priorBatchCount() < 2);
        assertThat(report.baseline() == null).isEqualTo(report.insufficientHistory());
        assertThat(report.metrics()).isNotNull();
    }

    @Test
    void aThirdBatchIsJudgedAgainstTheTwoBeforeIt() throws IOException {
        reconcile(ordinaryOrders("B"), settlement("B", 3), bank("B", 3));
        reconcile(ordinaryOrders("C"), settlement("C", 3), bank("C", 3));
        UUID degraded = reconcile(degradedOrders("D"), settlement("D", 3), bank("D", 3));

        HealthReport report = healthService.report(degraded);

        assertThat(report.insufficientHistory()).isFalse();
        assertThat(report.priorBatchCount()).isGreaterThanOrEqualTo(2);
        assertThat(report.baseline()).isNotNull();

        // Three disputes against a baseline of one is exactly three times, which warns.
        AlertView disputes = alertFor(report.alerts(), "dispute_rate");
        assertThat(disputes).isNotNull();
        assertThat(disputes.currentValue()).isEqualByComparingTo("0.750000");
        assertThat(disputes.baselineValue()).isEqualByComparingTo("0.250000");
        assertThat(disputes.ratio()).isEqualByComparingTo("3.000000");
        assertThat(disputes.severity()).isEqualTo("WARN");
        assertThat(disputes.sourceRowIds()).isNotEmpty();
    }

    @Test
    void anHourThatFailsFarMoreThanTheBatchIsFlaggedOnItsOwn() throws IOException {
        reconcile(ordinaryOrders("E"), settlement("E", 3), bank("E", 3));
        reconcile(ordinaryOrders("F"), settlement("F", 3), bank("F", 3));
        UUID degraded = reconcile(degradedOrders("G"), settlement("G", 3), bank("G", 3));

        List<AlertView> hourAlerts = healthService.report(degraded).alerts().stream()
                .filter(alert -> alert.metric().startsWith("failure_rate_hour_"))
                .toList();

        // The only failure sits at 03:00, which is four times the batch's own 25% rate.
        assertThat(hourAlerts).singleElement().satisfies(alert -> {
            assertThat(alert.metric()).isEqualTo("failure_rate_hour_03");
            assertThat(alert.currentValue()).isEqualByComparingTo("1.000000");
            assertThat(alert.ratio()).isEqualByComparingTo("4.000000");
            assertThat(alert.severity()).isEqualTo("HIGH");
        });
    }

    @Test
    void everyAlertIsWrittenToTheAuditLogAndKeepsItsDrivingRows() throws IOException {
        reconcile(ordinaryOrders("H"), settlement("H", 3), bank("H", 3));
        reconcile(ordinaryOrders("I"), settlement("I", 3), bank("I", 3));
        UUID degraded = reconcile(degradedOrders("J"), settlement("J", 3), bank("J", 3));

        HealthReport report = healthService.report(degraded);

        assertThat(report.alerts()).isNotEmpty();
        assertThat(report.alerts()).allSatisfy(alert -> {
            assertThat(alert.sourceRowIds()).isNotEmpty().hasSizeLessThanOrEqualTo(50);
            assertThat(alert.severity()).isIn("WARN", "HIGH");
            assertThat(alert.baselineValue()).isNotNull();
        });
    }

    @Test
    void theEndpointsServeTheStripAndItsHistory() throws Exception {
        reconcile(ordinaryOrders("K"), settlement("K", 3), bank("K", 3));
        reconcile(ordinaryOrders("L"), settlement("L", 3), bank("L", 3));
        UUID degraded = reconcile(degradedOrders("M"), settlement("M", 3), bank("M", 3));

        mockMvc.perform(get("/api/health/" + degraded))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insufficientHistory").value(false))
                .andExpect(jsonPath("$.metrics.feeRate").exists())
                .andExpect(jsonPath("$.metrics.failureRateByHour").isNotEmpty())
                .andExpect(jsonPath("$.baseline").exists())
                .andExpect(jsonPath("$.alerts").isNotEmpty());

        mockMvc.perform(get("/api/health/" + degraded + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].metrics.matchRate").exists());

        mockMvc.perform(get("/api/health/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private static AlertView alertFor(List<AlertView> alerts, String metric) {
        return alerts.stream().filter(alert -> alert.metric().equals(metric)).findFirst().orElse(null);
    }

    private UUID reconcile(String orders, String settlement, String bank) throws IOException {
        UUID batchId = ingestService.ingest(stream(orders), stream(settlement), stream(bank)).batchId();
        reconciliationService.reconcile(batchId);
        return batchId;
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
