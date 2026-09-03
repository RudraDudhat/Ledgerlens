package com.ledgerlens;

import com.ledgerlens.dto.IngestResponse;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The download itself: what it is, what it is called, and the two ways it can legitimately refuse.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.google.genai.api-key=stub-key-never-sent",
        "spring.ai.google.genai.chat.options.model=gemini-3.6-flash"
})
@AutoConfigureMockMvc
@Import(StatementControllerTest.StubChatModelConfiguration.class)
class StatementControllerTest {

    private static final Path DATA_DIR = Path.of("..", "data");

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
            ORD-S1,2026-08-18T10:00:00,1000.00,pay_S1,UPI,CAPTURED,,
            """;
    private static final String SETTLEMENT = """
            utr,settled_on,entity_type,entity_id,order_id,method,gross_amount,fee,gst,net_amount
            UTRSSS111111,2026-08-19,payment,pay_S1,ORD-S1,UPI,1000.00,0.00,0.00,1000.00
            """;
    private static final String BANK = """
            entry_date,description,utr,credit_amount
            2026-08-19,NEFT/UTRSSS111111/RAZORPAY,UTRSSS111111,1000.00
            """;

    @Autowired
    CsvIngestService ingestService;
    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    StubChatModel chatModel;
    @Autowired
    MockMvc mockMvc;

    @Test
    void servesAPdfNamedAfterThePeriodItCovers() throws Exception {
        UUID batchId = ingestSmallBatch();
        reconciliationService.reconcile(batchId);
        chatModel.reset();

        MvcResult result = mockMvc.perform(get("/api/reconcile/{batchId}/statement.pdf", batchId))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment;")
                .contains("filename=\"ledgerlens-statement-2026-08-18.pdf\"");
        assertThat(result.getResponse().getContentAsByteArray()).startsWith('%', 'P', 'D', 'F', '-');
        assertThat(chatModel.prompts()).isEmpty();
    }

    /** The 300-row batch spans days, so the filename has to carry both ends of the window. */
    @Test
    void namesAMultiDayBatchWithBothEnds() throws Exception {
        UUID batchId = ingestCommittedBatch();
        reconciliationService.reconcile(batchId);

        MvcResult result = mockMvc.perform(get("/api/reconcile/{batchId}/statement.pdf", batchId))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .containsPattern("filename=\"ledgerlens-statement-\\d{4}-\\d{2}-\\d{2}_to_\\d{4}-\\d{2}-\\d{2}\\.pdf\"");
    }

    @Test
    void refusesAnUnknownBatchWithNotFound() throws Exception {
        mockMvc.perform(get("/api/reconcile/{batchId}/statement.pdf", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesABatchThatWasNeverReconciledWithConflict() throws Exception {
        UUID batchId = ingestSmallBatch();

        mockMvc.perform(get("/api/reconcile/{batchId}/statement.pdf", batchId))
                .andExpect(status().isConflict());
    }

    private UUID ingestSmallBatch() throws IOException {
        IngestResponse response = ingestService.ingest(stream(ORDERS), stream(SETTLEMENT), stream(BANK));
        return response.batchId();
    }

    private UUID ingestCommittedBatch() throws IOException {
        return ingestService.ingest(
                Files.newInputStream(DATA_DIR.resolve(com.ledgerlens.service.SyntheticDataWriter.ORDERS_FILE)),
                Files.newInputStream(DATA_DIR.resolve(com.ledgerlens.service.SyntheticDataWriter.SETTLEMENT_FILE)),
                Files.newInputStream(DATA_DIR.resolve(com.ledgerlens.service.SyntheticDataWriter.BANK_FILE)))
                .batchId();
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
