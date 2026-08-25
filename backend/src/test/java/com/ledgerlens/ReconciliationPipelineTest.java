package com.ledgerlens;

import com.ledgerlens.dto.AnswerKey;
import com.ledgerlens.dto.IngestResponse;
import com.ledgerlens.dto.ReconcileSummary;
import com.ledgerlens.entity.AuditLog;
import com.ledgerlens.entity.ExceptionStatus;
import com.ledgerlens.entity.Payment;
import com.ledgerlens.repository.AuditLogRepository;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.repository.DisputeRepository;
import com.ledgerlens.repository.MatchRecordRepository;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.ledgerlens.repository.PaymentRepository;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.repository.SettlementLineRepository;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.ReconciliationService;
import com.ledgerlens.service.SyntheticDataWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the committed 300-row batch through ingest and matching and reports what it actually
 * achieves. The match rate is expected to sit below 100%: failed payments and payments held behind a
 * dispute are legitimately absent from the settlement report, and no rule invents a match for them.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationPipelineTest {

    private static final Path DATA_DIR = Path.of("..", "data");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static AnswerKey answerKey;

    @Autowired
    CsvIngestService ingestService;
    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    MerchantOrderRepository orderRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    DisputeRepository disputeRepository;
    @Autowired
    SettlementBatchRepository settlementBatchRepository;
    @Autowired
    SettlementLineRepository settlementLineRepository;
    @Autowired
    BankEntryRepository bankEntryRepository;
    @Autowired
    MatchRecordRepository matchRepository;
    @Autowired
    AuditLogRepository auditLogRepository;
    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void loadAnswerKey() throws IOException {
        Path path = DATA_DIR.resolve(SyntheticDataWriter.ANSWER_KEY_FILE);
        assertThat(path).as("run the generate profile before this test").exists();
        answerKey = SyntheticDataWriter.answerKeyMapper().readValue(path.toFile(), AnswerKey.class);
    }

    @Test
    void ingestLandsEveryRowOfTheCommittedBatch() throws IOException {
        IngestResponse response = ingestCommittedBatch();

        assertThat(response.orders()).isEqualTo(answerKey.count());
        assertThat(response.payments()).isEqualTo(answerKey.count());
        assertThat(response.disputes()).isEqualTo(countOf(ExceptionStatus.HELD_DISPUTE));
        assertThat(response.settlementLines()).isPositive();
        assertThat(response.bankEntries()).isPositive();

        UUID batchId = response.batchId();
        assertThat(orderRepository.countByBatchId(batchId)).isEqualTo(answerKey.count());
        assertThat(paymentRepository.countByBatchId(batchId)).isEqualTo(answerKey.count());
        assertThat(settlementLineRepository.countByBatchId(batchId)).isEqualTo(response.settlementLines());
        assertThat(bankEntryRepository.countByBatchId(batchId)).isEqualTo(response.bankEntries());
        assertThat(disputeRepository.countByBatchId(batchId)).isEqualTo(countOf(ExceptionStatus.HELD_DISPUTE));
    }

    @Test
    void settlementReportOverwritesPredictedFeesAndFailedPaymentsCostNothing() throws IOException {
        UUID batchId = ingestCommittedBatch().batchId();

        List<Payment> payments = paymentRepository.findByBatchIdOrderById(batchId);
        BigDecimal totalFees = payments.stream().map(Payment::getFee).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalGst = payments.stream().map(Payment::getGst).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalFees).isEqualByComparingTo(answerKey.totals().totalFees());
        assertThat(totalGst).isEqualByComparingTo(answerKey.totals().totalGst());
        payments.stream()
                .filter(payment -> payment.getStatus().name().equals("FAILED"))
                .forEach(payment -> {
                    assertThat(payment.getFee()).isEqualByComparingTo("0.00");
                    assertThat(payment.getNetAmount()).isEqualByComparingTo("0.00");
                });
    }

    @Test
    void matchRateBeatsTheCleanRecordShare() throws IOException {
        UUID batchId = ingestCommittedBatch().batchId();

        ReconcileSummary summary = reconciliationService.reconcile(batchId);

        int failed = countOf(ExceptionStatus.PAYMENT_FAILED);
        int held = countOf(ExceptionStatus.HELD_DISPUTE);
        int refundedInWindow = (int) answerKey.anomalies().stream()
                .filter(a -> a.type() == ExceptionStatus.REFUND_PRIOR_CYCLE && !a.preWindow())
                .count();
        BigDecimal cleanShare = share(answerKey.count() - failed - held - refundedInWindow);

        // Every order settles except the ones that failed or are held, so those two are the whole gap.
        assertThat(summary.matchedOrderCount()).isEqualTo(answerKey.count() - failed - held);
        assertThat(summary.orderMatchRate()).isGreaterThanOrEqualTo(cleanShare);

        System.out.printf("match rate %s over %d orders (clean-record share %s); %d failed, %d held%n",
                summary.orderMatchRate(), summary.orderCount(), cleanShare, failed, held);
    }

    @Test
    void everySettlementReachesTheBankExceptTheOnesTheBankNeverPosted() throws IOException {
        UUID batchId = ingestCommittedBatch().batchId();

        ReconcileSummary summary = reconciliationService.reconcile(batchId);

        int missing = countOf(ExceptionStatus.BANK_MISSING);
        int duplicated = countOf(ExceptionStatus.BANK_DUPLICATE);
        assertThat(summary.matchedSettlementBatchCount()).isEqualTo(summary.settlementBatchCount() - missing);
        // The extra copy of a duplicated credit stays unclaimed rather than being absorbed silently.
        assertThat(summary.bankEntryCount() - summary.matchedBankEntryCount()).isEqualTo(duplicated);
        assertThat(summary.totalSettled()).isEqualByComparingTo(answerKey.totals().totalSettled());
        assertThat(summary.totalBankCredits()).isEqualByComparingTo(answerKey.totals().totalBankCredits());
    }

    @Test
    void reRunningReconcileReplacesRatherThanAccumulatesMatches() throws IOException {
        UUID batchId = ingestCommittedBatch().batchId();

        ReconcileSummary first = reconciliationService.reconcile(batchId);
        long afterFirst = matchRepository.countByBatchId(batchId);
        ReconcileSummary second = reconciliationService.reconcile(batchId);

        assertThat(matchRepository.countByBatchId(batchId)).isEqualTo(afterFirst);
        assertThat(second.matchedOrderCount()).isEqualTo(first.matchedOrderCount());
        assertThat(second.matchesByType()).isEqualTo(first.matchesByType());
    }

    @Test
    void everyReconcileRunIsRecordedInTheAuditLog() throws IOException {
        UUID batchId = ingestCommittedBatch().batchId();

        reconciliationService.reconcile(batchId);
        reconciliationService.reconcile(batchId);

        List<AuditLog> entries = auditLogRepository.findByBatchIdOrderById(batchId);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getAction()).isEqualTo("RECONCILE");
        assertThat(entries.get(0).getDetail()).contains("orders to settlement lines");
    }

    @Test
    void aStatementWithNoUtrColumnStillReconciles() throws IOException {
        String orders = """
                order_id,order_ts,amount,payment_id,method,payment_status,dispute_status,dispute_opened_at
                ORD-T1,2026-07-27T10:00:00,1000.00,pay_T1,UPI,CAPTURED,,
                ORD-T2,2026-07-27T11:00:00,2000.00,pay_T2,UPI,CAPTURED,,
                """;
        String settlement = """
                utr,settled_on,entity_type,entity_id,order_id,method,gross_amount,fee,gst,net_amount
                UTRAAA111111,2026-07-28,payment,pay_T1,ORD-T1,UPI,1000.00,0.00,0.00,1000.00
                UTRBBB222222,2026-07-29,payment,pay_T2,ORD-T2,UPI,2000.00,0.00,0.00,2000.00
                """;
        String bank = """
                entry_date,description,utr,credit_amount
                2026-07-28,NEFT/UTRAAA111111/RAZORPAY SOFTWARE PVT LTD,,1000.00
                2026-07-29,COLLECTION CREDIT NO REFERENCE,,2000.00
                """;

        UUID batchId = ingestService.ingest(stream(orders), stream(settlement), stream(bank)).batchId();
        ReconcileSummary summary = reconciliationService.reconcile(batchId);

        assertThat(summary.matchedOrderCount()).isEqualTo(2);
        assertThat(summary.matchedSettlementBatchCount()).isEqualTo(2);
        assertThat(summary.matchesByType()).containsEntry("UTR_EXACT", 1).containsEntry("AMOUNT_DATE_WINDOW", 1);
    }

    @Test
    void aCsvMissingAColumnIsRejectedByName() {
        String orders = "order_id,amount\nORD-T1,1000.00\n";
        String settlement = "utr\nUTR1\n";
        String bank = "entry_date\n2026-07-28\n";

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> ingestService.ingest(stream(orders), stream(settlement), stream(bank))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orders file is missing columns")
                .hasMessageContaining("order_ts");
    }

    @Test
    void theHttpApiCarriesTheWholeFlow() throws Exception {
        String batchId = mockMvc.perform(multipart("/api/ingest/csv")
                        .file(filePart("orders", SyntheticDataWriter.ORDERS_FILE))
                        .file(filePart("settlement", SyntheticDataWriter.SETTLEMENT_FILE))
                        .file(filePart("bank", SyntheticDataWriter.BANK_FILE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").value(answerKey.count()))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"batchId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/reconcile/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(answerKey.count()));

        mockMvc.perform(get("/api/reconcile/" + batchId + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderMatchRate").exists());

        mockMvc.perform(get("/api/reconcile/" + batchId + "/matches").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.content[0].matchType").exists());

        mockMvc.perform(get("/api/reconcile/" + UUID.randomUUID() + "/summary"))
                .andExpect(status().isNotFound());
    }

    @Test
    void matchViewsJoinTheRowsTheyPointAt() throws IOException {
        UUID batchId = ingestCommittedBatch().batchId();
        reconciliationService.reconcile(batchId);

        var page = reconciliationService.matches(batchId, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(matchRepository.countByBatchId(batchId));
        assertThat(page.getContent()).allSatisfy(view -> {
            assertThat(view.matchType()).isNotBlank();
            assertThat(view.amount()).isNotNull();
            assertThat(view.utr()).isNotBlank();
        });
    }

    private IngestResponse ingestCommittedBatch() throws IOException {
        return ingestService.ingest(
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.ORDERS_FILE)),
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.SETTLEMENT_FILE)),
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.BANK_FILE)));
    }

    private static MockMultipartFile filePart(String partName, String fileName) throws IOException {
        return new MockMultipartFile(partName, fileName, "text/csv",
                Files.readAllBytes(DATA_DIR.resolve(fileName)));
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static int countOf(ExceptionStatus type) {
        return (int) answerKey.anomalies().stream().filter(a -> a.type() == type).count();
    }

    private static BigDecimal share(int matched) {
        return BigDecimal.valueOf(matched)
                .divide(BigDecimal.valueOf(answerKey.count()), 4, java.math.RoundingMode.HALF_UP);
    }
}
