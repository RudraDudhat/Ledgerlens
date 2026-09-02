package com.ledgerlens;

import com.ledgerlens.dto.AskResponse;
import com.ledgerlens.dto.IngestResponse;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.QuestionAnswerer;
import com.ledgerlens.service.RagIndexer;
import com.ledgerlens.service.RagRetriever;
import com.ledgerlens.service.RagStore;
import com.ledgerlens.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The hybrid Ask path: what it retrieves, what it refuses to retrieve, and what it never touches.
 *
 * <p>No embedding model and no pgvector are involved. The store is a fake, which is the point — the
 * invariant worth testing is not that similarity search is good, it is that a question asked about
 * one batch cannot reach another batch's rows, and that reconciliation survives indexing failing.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.google.genai.api-key=stub-key-never-sent",
        "spring.ai.google.genai.chat.options.model=gemini-3.6-flash"
})
@Import(RagTest.FakesConfiguration.class)
class RagTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @TestConfiguration
    static class FakesConfiguration {
        @Bean
        @Primary
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }

        @Bean
        @Primary
        FakeRagStore fakeRagStore() {
            return new FakeRagStore();
        }

        /**
         * Indexing is @Async in production so reconcile does not wait on the embedding provider.
         * Here it runs on the calling thread, so a test can assert what was indexed straight after
         * reconcile instead of racing a background thread.
         */
        @Bean
        TaskExecutor taskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    private static final String ORDERS = """
            order_id,order_ts,amount,payment_id,method,payment_status,dispute_status,dispute_opened_at
            ORD-R1,2026-07-27T10:00:00,1000.00,pay_R1,UPI,CAPTURED,,
            """;
    private static final String SETTLEMENT = """
            utr,settled_on,entity_type,entity_id,order_id,method,gross_amount,fee,gst,net_amount
            UTRRRR111111,2026-07-28,payment,pay_R1,ORD-R1,UPI,1000.00,0.00,0.00,1000.00
            """;
    private static final String BANK = """
            entry_date,description,utr,credit_amount
            2026-07-28,NEFT/UTRRRR111111/RAZORPAY,UTRRRR111111,1000.00
            2026-08-14,NEFT/UTRZZZ999999/UNRECOGNISED,UTRZZZ999999,7777.77
            """;

    @Autowired
    CsvIngestService ingestService;
    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    QuestionAnswerer questionAnswerer;
    @Autowired
    RagIndexer ragIndexer;
    @Autowired
    RagRetriever ragRetriever;
    @Autowired
    RagStore store;
    @Autowired
    StubChatModel chatModel;

    FakeRagStore fake() {
        return (FakeRagStore) store;
    }

    @BeforeEach
    void reset() {
        chatModel.reset();
        chatModel.respondWith("{\"answer\":\"stub\",\"citedRowIds\":[]}");
    }

    // ------------------------------------------------------------------ batch isolation

    /**
     * The invariant this whole feature lives or dies by.
     *
     * <p>Batch A holds a document that would match "mismatch" perfectly. Batch B holds nothing of the
     * kind. Asking batch B about mismatches must come back with nothing at all — not a ranked-lower
     * hit from batch A, not a hit that is filtered out later, nothing.
     */
    @Test
    void aQuestionAboutOneBatchNeverReachesAnother() throws IOException {
        UUID batchA = reconciledBatch();
        UUID batchB = reconciledBatch();
        fake().seed(batchA, document(batchA, "A-1",
                "Exception AMOUNT_MISMATCH on UTR-A. The bank credited a settlement mismatch of 49.00."));

        String question = "which records look like a settlement mismatch";

        assertThat(ragRetriever.search(question, batchA))
                .as("batch A owns the document, so it is among its hits")
                .anyMatch(hit -> "A-1".equals(hit.getMetadata().get("recordId")));

        // Both batches were reconciled, so batch B has hits of its own. Not one of them may be A's.
        assertThat(ragRetriever.search(question, batchB))
                .as("no hit answering for batch B may belong to batch A")
                .allSatisfy(hit -> assertThat(hit.getMetadata().get(RagIndexer.BATCH_KEY))
                        .isEqualTo(batchB.toString()));
    }

    /** A document that lies about its batch is dropped even if the store hands it over. */
    @Test
    void aHitClaimingAnotherBatchIsDiscarded() throws IOException {
        UUID batchA = reconciledBatch();
        UUID batchB = reconciledBatch();
        // Seeded under B, but its metadata says A — exactly what a filtering bug would look like.
        fake().seed(batchB, document(batchA, "A-2", "Exception AMOUNT_MISMATCH mismatch on UTR-A."));

        assertThat(ragRetriever.search("mismatch", batchB)).isEmpty();
    }

    // ------------------------------------------------------------------ router

    @Nested
    class Router {

        @Test
        void aQuestionNamingAnOrderNeverSearches() throws IOException {
            UUID batchId = reconciledBatch();
            fake().searchedBatches().clear();

            questionAnswerer.ask(batchId, "What happened to order ORD-R1?");

            assertThat(fake().searchedBatches()).as("exact anchors keep the SQL-only path").isEmpty();
        }

        @Test
        void aQuestionWithNoAnchorSearches() throws IOException {
            UUID batchId = reconciledBatch();
            fake().searchedBatches().clear();

            questionAnswerer.ask(batchId, "why did we receive less money");

            assertThat(fake().searchedBatches()).containsExactly(batchId);
        }

        @Test
        void aDefinitionalQuestionNeitherQueriesNorSearches() throws IOException {
            UUID batchId = reconciledBatch();
            fake().searchedBatches().clear();
            chatModel.respondWith("Reconciliation is checking three records against each other.");

            AskResponse answer = questionAnswerer.ask(batchId, "what is reconciliation");

            assertThat(answer.answerKind()).isEqualTo(AskResponse.AnswerKind.CONCEPTUAL);
            assertThat(answer.citations()).isEmpty();
            assertThat(fake().searchedBatches()).isEmpty();
            assertThat(chatModel.lastPrompt()).contains("STATUS DEFINITIONS");
        }

        @Test
        void anAmountQuestionAboutAnOrderStaysOnTheSqlPath() {
            assertThat(QuestionAnswerer.isConceptual("what is the amount of ORD-201")).isFalse();
            assertThat(QuestionAnswerer.hasExactAnchor("what is the amount of ORD-201")).isTrue();
        }

        /** The phrasing that shipped broken: a word between "the" and "batch" defeated the guard. */
        @Test
        void aQuestionAboutTheCurrentBatchIsNotADefinition() {
            assertThat(QuestionAnswerer.isConceptual("explain the reconciliation for the current batch")).isFalse();
            assertThat(QuestionAnswerer.isConceptual("summarize my batch")).isFalse();
            assertThat(QuestionAnswerer.isConceptual("what is reconciliation")).isTrue();
        }

        @Test
        void explainingSomethingInThisBatchIsNotADefinition() {
            assertThat(QuestionAnswerer.isConceptual("explain the mismatch in this batch")).isFalse();
            assertThat(QuestionAnswerer.hasExactAnchor("explain the mismatch in this batch")).isFalse();
        }
    }

    // ------------------------------------------------------------------ indexer

    @Nested
    class Indexer {

        @Test
        void reconcilingTwiceDoesNotDoubleTheCorpus() throws IOException {
            UUID batchId = reconciledBatch();
            int afterFirst = store.countFor(batchId);

            reconciliationService.reconcile(batchId);

            assertThat(afterFirst).isPositive();
            assertThat(store.countFor(batchId)).isEqualTo(afterFirst);
        }

        @Test
        void indexingFailureDoesNotFailReconcile() throws IOException {
            UUID batchId = reconciledBatch();
            fake().failOnNextWrite(new IllegalStateException("embedding provider is down"));

            assertThatCode(() -> reconciliationService.reconcile(batchId)).doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Document document(UUID batchId, String recordId, String text) {
        return new Document(UUID.randomUUID().toString(), text, Map.of(
                RagIndexer.BATCH_KEY, batchId.toString(),
                "recordId", recordId,
                "recordType", "EXCEPTION"));
    }

    private UUID reconciledBatch() throws IOException {
        IngestResponse response = ingestService.ingest(stream(ORDERS), stream(SETTLEMENT), stream(BANK));
        reconciliationService.reconcile(response.batchId());
        return response.batchId();
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
