package com.ledgerlens;

import com.ledgerlens.dto.IngestResponse;
import com.ledgerlens.entity.Payment;
import com.ledgerlens.entity.PaymentMethod;
import com.ledgerlens.entity.PaymentStatus;
import com.ledgerlens.entity.Refund;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.entity.SettlementLine;
import com.ledgerlens.entity.SettlementLineType;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.ledgerlens.repository.PaymentRepository;
import com.ledgerlens.repository.RefundRepository;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.repository.SettlementLineRepository;
import com.ledgerlens.service.RazorpayIngestService;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the Razorpay path without a key or a network: the refusal when credentials are absent, and
 * the mapping from recorded API payloads into stored rows.
 *
 * <p>Amounts from Razorpay arrive in paise as integers, which is the easiest thing in this whole
 * project to get wrong by a factor of a hundred, so every assertion here is on the rupee value.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RazorpayIngestTest {

    private static final ZoneId MERCHANT_ZONE = ZoneId.of("Asia/Kolkata");

    /** 2026-07-27T10:00 IST and 2026-07-28T10:00 IST. */
    private static final long CAPTURED_AT = LocalDateTime.of(2026, 7, 27, 10, 0)
            .atZone(MERCHANT_ZONE).toEpochSecond();
    private static final long REFUNDED_AT = LocalDateTime.of(2026, 7, 28, 10, 0)
            .atZone(MERCHANT_ZONE).toEpochSecond();
    private static final long SETTLED_AT = LocalDateTime.of(2026, 7, 29, 11, 0)
            .atZone(MERCHANT_ZONE).toEpochSecond();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    RazorpayIngestService razorpayIngestService;
    @Autowired
    IngestBatchRepository ingestBatchRepository;
    @Autowired
    MerchantOrderRepository orderRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    RefundRepository refundRepository;
    @Autowired
    SettlementBatchRepository settlementBatchRepository;
    @Autowired
    SettlementLineRepository settlementLineRepository;
    @Autowired
    BankEntryRepository bankEntryRepository;
    @Autowired
    MockMvc mockMvc;

    @Test
    void withoutCredentialsItSaysWhatIsMissingAndPointsAtTheCsvPath() {
        assertThat(razorpayIngestService.configured()).isFalse();

        ResponseStatusException refusal = catchThrowableOfType(
                () -> razorpayIngestService.ingest(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 31)),
                ResponseStatusException.class);

        assertThat(refusal.getStatusCode().value()).isEqualTo(503);
        assertThat(refusal.getReason())
                .contains("RAZORPAY_KEY_ID")
                .contains("RAZORPAY_KEY_SECRET")
                .contains("/api/ingest/csv");
    }

    @Test
    void theEndpointRefusesRatherThanFailingSomewhereUpstream() throws Exception {
        mockMvc.perform(post("/api/ingest/razorpay")
                        .contentType("application/json")
                        .content("{\"from\":\"2026-07-27\",\"to\":\"2026-07-31\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void anInvalidRangeIsRejectedBeforeAnythingIsStored() throws Exception {
        mockMvc.perform(post("/api/ingest/razorpay")
                        .contentType("application/json")
                        .content("{\"from\":null,\"to\":\"2026-07-31\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void paiseFromTheApiBecomeRupeesInTheLedger() {
        IngestResponse response = razorpayIngestService.persist(
                List.of(capturedPayment(), failedPayment()), List.of(refund()), List.of());

        List<Payment> payments = paymentRepository.findByBatchIdOrderById(response.batchId());
        assertThat(payments).hasSize(2);

        Payment captured = payments.get(0);
        assertThat(captured.getPaymentId()).isEqualTo("pay_captured");
        assertThat(captured.getAmount()).isEqualByComparingTo("5000.00");
        assertThat(captured.getFee()).isEqualByComparingTo("100.00");
        assertThat(captured.getGst()).isEqualByComparingTo("18.00");
        assertThat(captured.getNetAmount()).isEqualByComparingTo("4882.00");
        assertThat(captured.getMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(captured.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(captured.getCreatedAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 27));

        Payment failed = payments.get(1);
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(failed.getNetAmount()).as("a failed payment costs nothing").isEqualByComparingTo("0.00");
    }

    @Test
    void refundsArriveWithTheirOwnTimingAndThePaymentTheyBelongTo() {
        IngestResponse response = razorpayIngestService.persist(
                List.of(capturedPayment()), List.of(refund()), List.of());

        List<Refund> refunds = refundRepository.findByBatchIdOrderById(response.batchId());

        assertThat(refunds).singleElement().satisfies(refund -> {
            assertThat(refund.getRefundId()).isEqualTo("rfnd_1");
            assertThat(refund.getPaymentId()).isEqualTo("pay_captured");
            assertThat(refund.getOrderId()).as("resolved through the payment").isEqualTo("order_captured");
            assertThat(refund.getAmount()).isEqualByComparingTo("1500.00");
            assertThat(refund.getCreatedAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 28));
        });
    }

    @Test
    void reconLinesBecomeSettlementBatchesNettedByUtr() {
        IngestResponse response = razorpayIngestService.persist(
                List.of(capturedPayment()), List.of(refund()), List.of(reconPayment(), reconRefund()));
        UUID batchId = response.batchId();

        List<SettlementBatch> settlements = settlementBatchRepository.findByBatchIdOrderBySettledOn(batchId);
        assertThat(settlements).singleElement().satisfies(settlement -> {
            assertThat(settlement.getUtr()).isEqualTo("UTR20260729");
            assertThat(settlement.getSettledOn()).isEqualTo(LocalDate.of(2026, 7, 29));
            // 4882.00 credited less 1500.00 debited.
            assertThat(settlement.getAmount()).isEqualByComparingTo("3382.00");
        });

        List<SettlementLine> lines = settlementLineRepository.findByBatchIdOrderById(batchId);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getLineType()).isEqualTo(SettlementLineType.PAYMENT);
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("5000.00");
        assertThat(lines.get(1).getLineType()).isEqualTo(SettlementLineType.REFUND);
        assertThat(lines.get(1).getAmount()).as("refund lines are negative").isEqualByComparingTo("-1500.00");
    }

    @Test
    void razorpayCannotSeeTheBankSoNoBankRowsAreInvented() {
        IngestResponse response = razorpayIngestService.persist(
                List.of(capturedPayment()), List.of(refund()), List.of(reconPayment()));

        assertThat(response.bankEntries()).isZero();
        assertThat(bankEntryRepository.countByBatchId(response.batchId())).isZero();
    }

    @Test
    void aPaymentWithNoOrderOrAnUnsupportedMethodIsSkippedRatherThanGuessedAt() {
        JSONObject noOrder = capturedPayment();
        noOrder.remove("order_id");
        JSONObject payLater = capturedPayment().put("id", "pay_paylater").put("method", "paylater");
        JSONObject notYetMoney = capturedPayment().put("id", "pay_authorized").put("status", "authorized");

        IngestResponse response = razorpayIngestService.persist(
                List.of(noOrder, payLater, notYetMoney, capturedPayment()), List.of(), List.of());

        assertThat(response.payments()).isEqualTo(1);
        assertThat(orderRepository.countByBatchId(response.batchId())).isEqualTo(1);
    }

    @Test
    void theBatchIsRecordedAsComingFromRazorpay() {
        IngestResponse response = razorpayIngestService.persist(List.of(capturedPayment()), List.of(), List.of());

        assertThat(ingestBatchRepository.findById(response.batchId()))
                .get()
                .satisfies(batch -> assertThat(batch.getSource().name()).isEqualTo("RAZORPAY"));
    }

    // ---------- recorded payloads, shaped the way the API returns them ----------

    private static JSONObject capturedPayment() {
        return new JSONObject()
                .put("id", "pay_captured")
                .put("order_id", "order_captured")
                .put("amount", 500000)
                .put("fee", 10000)
                .put("tax", 1800)
                .put("method", "card")
                .put("status", "captured")
                .put("created_at", CAPTURED_AT);
    }

    private static JSONObject failedPayment() {
        return new JSONObject()
                .put("id", "pay_failed")
                .put("order_id", "order_failed")
                .put("amount", 250000)
                .put("method", "upi")
                .put("status", "failed")
                .put("created_at", CAPTURED_AT);
    }

    private static JSONObject refund() {
        return new JSONObject()
                .put("id", "rfnd_1")
                .put("payment_id", "pay_captured")
                .put("amount", 150000)
                .put("created_at", REFUNDED_AT);
    }

    private static JSONObject reconPayment() {
        return new JSONObject()
                .put("entity_id", "pay_captured")
                .put("type", "payment")
                .put("amount", 500000)
                .put("credit", 488200)
                .put("debit", 0)
                .put("order_id", "order_captured")
                .put("utr", "UTR20260729")
                .put("settled_at", SETTLED_AT);
    }

    private static JSONObject reconRefund() {
        return new JSONObject()
                .put("entity_id", "rfnd_1")
                .put("type", "refund")
                .put("amount", 150000)
                .put("credit", 0)
                .put("debit", 150000)
                .put("order_id", "order_captured")
                .put("utr", "UTR20260729")
                .put("settled_at", SETTLED_AT);
    }
}
