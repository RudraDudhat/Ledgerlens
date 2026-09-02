package com.ledgerlens;

import com.ledgerlens.dto.AskResponse;
import com.ledgerlens.dto.IngestResponse;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.QuestionAnswerer;
import com.ledgerlens.service.RagStore;
import com.ledgerlens.service.ReconciliationService;
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
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The flag off. A separate class because a nested {@code @SpringBootTest} reuses the enclosing
 * context rather than building one with different properties, so the override would be ignored.
 *
 * <p>What must hold: with {@code ledgerlens.rag.enabled=false} the store is never searched, nothing
 * is indexed, and a question with no exact anchor gets the refusal it always got.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.google.genai.api-key=stub-key-never-sent",
        "spring.ai.google.genai.chat.options.model=gemini-3.6-flash",
        "ledgerlens.rag.enabled=false"
})
@Import(RagDisabledTest.FakesConfiguration.class)
class RagDisabledTest {

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
    }

    private static final String ORDERS = """
            order_id,order_ts,amount,payment_id,method,payment_status,dispute_status,dispute_opened_at
            ORD-D1,2026-07-27T10:00:00,1000.00,pay_D1,UPI,CAPTURED,,
            """;
    private static final String SETTLEMENT = """
            utr,settled_on,entity_type,entity_id,order_id,method,gross_amount,fee,gst,net_amount
            UTRDDD111111,2026-07-28,payment,pay_D1,ORD-D1,UPI,1000.00,0.00,0.00,1000.00
            """;
    private static final String BANK = """
            entry_date,description,utr,credit_amount
            2026-07-28,NEFT/UTRDDD111111/RAZORPAY,UTRDDD111111,1000.00
            """;

    @Autowired
    CsvIngestService ingestService;
    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    QuestionAnswerer questionAnswerer;
    @Autowired
    RagStore store;
    @Autowired
    StubChatModel chatModel;

    @BeforeEach
    void reset() {
        chatModel.reset();
        chatModel.respondWith("{\"answer\":\"stub\",\"citedRowIds\":[]}");
    }

    @Test
    void nothingIsIndexedAndNothingIsSearched() throws IOException {
        UUID batchId = reconciledBatch();
        FakeRagStore fake = (FakeRagStore) store;

        questionAnswerer.ask(batchId, "What happened to order ORD-D1?");
        AskResponse fuzzy = questionAnswerer.ask(batchId, "why did we receive less money");

        assertThat(fake.countFor(batchId)).as("reconcile indexed nothing").isZero();
        assertThat(fake.searchedBatches()).as("no search happened").isEmpty();
        assertThat(fuzzy.answer())
                .as("a question with no anchor refuses exactly as it did before vectors existed")
                .isEqualTo(QuestionAnswerer.NOTHING_FOUND);
        assertThat(fuzzy.answerKind()).isEqualTo(AskResponse.AnswerKind.FACTUAL);
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
