package com.ledgerlens;

import com.ledgerlens.dto.AskResponse;
import com.ledgerlens.dto.ExceptionView;
import com.ledgerlens.dto.NarrativeResponse;
import com.ledgerlens.dto.WaterfallStep;
import com.ledgerlens.entity.AuditLog;
import com.ledgerlens.repository.AuditLogRepository;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.QuestionAnswerer;
import com.ledgerlens.service.ReconciliationService;
import com.ledgerlens.service.WaterfallNarrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the three model-backed features against a stub, so the suite needs no API key.
 *
 * <p>What is worth asserting here is not that the model says something sensible — a stub can be made
 * to say anything — but that the code around it holds the line: rules run first and the model only
 * sees what they could not settle, the narrator is handed finished numbers, a question with no
 * matching rows is refused before the model is called at all, and a malformed reply is discarded
 * rather than written over an honest UNKNOWN.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.google.genai.api-key=stub-key-never-sent",
        "spring.ai.google.genai.chat.options.model=gemini-3.6-flash",
        "ledgerlens.answer-key-path=../data/answer_key.json"
})
@Import(AiLayerTest.StubChatModelConfiguration.class)
class AiLayerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @TestConfiguration
    static class StubChatModelConfiguration {
        @Bean
        @Primary
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    private static final String ORDERS = """
            order_id,order_ts,amount,payment_id,method,payment_status,dispute_status,dispute_opened_at
            ORD-T1,2026-07-27T10:00:00,1000.00,pay_T1,UPI,CAPTURED,,
            """;
    private static final String SETTLEMENT = """
            utr,settled_on,entity_type,entity_id,order_id,method,gross_amount,fee,gst,net_amount
            UTRAAA111111,2026-07-28,payment,pay_T1,ORD-T1,UPI,1000.00,0.00,0.00,1000.00
            """;
    /** The second credit matches no settlement by reference, amount or date, so no rule can place it. */
    private static final String BANK = """
            entry_date,description,utr,credit_amount
            2026-07-28,NEFT/UTRAAA111111/RAZORPAY,UTRAAA111111,1000.00
            2026-08-14,NEFT/UTRZZZ999999/UNRECOGNISED,UTRZZZ999999,7777.77
            """;

    @Autowired
    StubChatModel chatModel;
    @Autowired
    CsvIngestService ingestService;
    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    QuestionAnswerer questionAnswerer;
    @Autowired
    WaterfallNarrator waterfallNarrator;
    @Autowired
    AuditLogRepository auditLogRepository;

    @BeforeEach
    void resetStub() {
        chatModel.reset();
    }

    @Test
    void theClassifierOnlySeesWhatTheRulesCouldNotSettle() throws IOException {
        chatModel.respondWith("""
                {"status":"BANK_DUPLICATE","reason":"The credit repeats an earlier settlement.","confidence":0.72}
                """);

        List<ExceptionView> exceptions = reconciliationService.exceptions(reconciledBatch());

        assertThat(chatModel.prompts()).hasSize(1);
        assertThat(chatModel.lastPrompt()).contains("UTRZZZ999999");
        assertThat(exceptions).singleElement().satisfies(exception -> {
            assertThat(exception.status()).isEqualTo("BANK_DUPLICATE");
            assertThat(exception.origin()).isEqualTo("LLM");
            assertThat(exception.confidence()).isEqualByComparingTo("0.720");
            assertThat(exception.reason()).isEqualTo("The credit repeats an earlier settlement.");
        });
    }

    @Test
    void aStatusTheModelInventedIsDiscardedRatherThanTrusted() throws IOException {
        chatModel.respondWith("""
                {"status":"PROBABLY_FINE","reason":"Looks alright to me.","confidence":0.99}
                """);

        List<ExceptionView> exceptions = reconciliationService.exceptions(reconciledBatch());

        assertThat(exceptions).singleElement().satisfies(exception -> {
            assertThat(exception.status()).isEqualTo("UNKNOWN");
            assertThat(exception.origin()).isEqualTo("RULE");
        });
    }

    @Test
    void anUnparseableReplyLeavesTheRecordUnknown() throws IOException {
        chatModel.respondWith("I am afraid I cannot help with that.");

        List<ExceptionView> exceptions = reconciliationService.exceptions(reconciledBatch());

        assertThat(exceptions).singleElement().satisfies(exception ->
                assertThat(exception.status()).isEqualTo("UNKNOWN"));
    }

    @Test
    void confidenceFromTheModelIsClampedIntoRange() throws IOException {
        chatModel.respondWith("""
                {"status":"BANK_MISSING","reason":"Nothing carries it.","confidence":4.2}
                """);

        List<ExceptionView> exceptions = reconciliationService.exceptions(reconciledBatch());

        assertThat(exceptions).singleElement().satisfies(exception ->
                assertThat(exception.confidence()).isEqualByComparingTo("1.000"));
    }

    @Test
    void theNarratorIsHandedFinishedNumbersAndNeverAskedToAddThemUp() throws IOException {
        UUID batchId = reconciledBatch();
        chatModel.reset();
        chatModel.respondWith("You sold 1,000 rupees and 1,000 rupees reached your bank.");

        NarrativeResponse narrative = waterfallNarrator.narrate(batchId);

        assertThat(narrative.narrative()).isEqualTo("You sold 1,000 rupees and 1,000 rupees reached your bank.");
        String prompt = chatModel.lastPrompt();
        assertThat(prompt).contains("Gross sales: 1000.00");
        assertThat(prompt).contains("Never compute");
        // Zero steps are dropped, so there is nothing for the model to narrate as "no refunds".
        assertThat(prompt).doesNotContain("Refunds: 0.00");
    }

    @Test
    void everyStepGivenToTheNarratorCarriesAnAmountThatWasAlreadyComputed() {
        List<WaterfallStep> steps = List.of(
                new WaterfallStep("Gross sales", new BigDecimal("1000.00"), List.of(1L)),
                new WaterfallStep("Refunds", new BigDecimal("0.00"), List.of()),
                new WaterfallStep("Razorpay fees", new BigDecimal("-20.00"), List.of(2L)));

        assertThat(WaterfallNarrator.render(steps))
                .isEqualTo("Gross sales: 1000.00\nRazorpay fees: -20.00");
    }

    @Test
    void aQuestionWithNoMatchingRowsIsRefusedBeforeTheModelIsCalled() throws IOException {
        UUID batchId = reconciledBatch();
        chatModel.reset();

        AskResponse answer = questionAnswerer.ask(batchId, "What happened to order ORD-DOES-NOT-EXIST?");

        assertThat(answer.answer()).isEqualTo(QuestionAnswerer.NOTHING_FOUND);
        assertThat(answer.citedRowIds()).isEmpty();
        assertThat(chatModel.prompts()).as("the model must not be asked to fill a gap").isEmpty();
    }

    @Test
    void anAnswerIsGroundedInTheRowsThatWereRetrieved() throws IOException {
        UUID batchId = reconciledBatch();
        chatModel.reset();
        chatModel.respondWith(AiLayerTest::citeFirstRow);

        AskResponse answer = questionAnswerer.ask(batchId, "What was order ORD-T1 worth?");

        assertThat(chatModel.lastPrompt()).contains("ORD-T1").contains("1000.00");
        assertThat(answer.answer()).isEqualTo("Order ORD-T1 was 1000.00 rupees.");
        assertThat(answer.citedRowIds()).hasSize(1);
        // A row id is a database key. What makes the citation checkable is the order id and amount.
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.kind()).isEqualTo("ORDER");
            assertThat(citation.ref()).isEqualTo("ORD-T1");
            assertThat(citation.amount()).isEqualByComparingTo("1000.00");
            assertThat(citation.date()).isEqualTo(LocalDate.of(2026, 7, 27));
        });
    }

    @Test
    void citationsTheModelInventedAreDroppedRatherThanEchoed() throws IOException {
        UUID batchId = reconciledBatch();
        chatModel.reset();
        chatModel.respondWith("""
                {"answer":"Order ORD-T1 was 1000.00 rupees.","citedRowIds":[999999]}
                """);

        AskResponse answer = questionAnswerer.ask(batchId, "What was order ORD-T1 worth?");

        assertThat(answer.citedRowIds()).isEmpty();
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void everyModelCallIsWrittenToTheAuditLog() throws IOException {
        UUID batchId = reconciledBatch();
        chatModel.reset();
        chatModel.respondWith("A short narration.");

        waterfallNarrator.narrate(batchId);

        List<AuditLog> narrations = auditLogRepository.findByBatchIdOrderById(batchId).stream()
                .filter(entry -> entry.getAction().equals("LLM_NARRATE"))
                .toList();
        assertThat(narrations).hasSize(1);
        assertThat(narrations.get(0).getDetail())
                .contains("model=gemini-3.6-flash")
                .contains("promptSha256=")
                .contains("latencyMs=")
                .contains("output=A short narration.");
    }

    private UUID reconciledBatch() throws IOException {
        UUID batchId = ingestService.ingest(stream(ORDERS), stream(SETTLEMENT), stream(BANK)).batchId();
        reconciliationService.reconcile(batchId);
        return batchId;
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** Cites whichever row id the prompt actually offered, the way a grounded answer would. */
    private static String citeFirstRow(String prompt) {
        Matcher rowId = Pattern.compile("\\[(\\d+)]").matcher(prompt);
        String id = rowId.find() ? rowId.group(1) : "0";
        return "{\"answer\":\"Order ORD-T1 was 1000.00 rupees.\",\"citedRowIds\":[" + id + "]}";
    }
}
