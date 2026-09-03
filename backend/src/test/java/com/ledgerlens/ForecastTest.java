package com.ledgerlens;

import com.ledgerlens.dto.AnswerKey;
import com.ledgerlens.dto.ForecastEntry;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.ForecastService;
import com.ledgerlens.service.ReconciliationService;
import com.ledgerlens.service.SyntheticDataWriter;
import org.junit.jupiter.api.BeforeAll;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks the forward calendar against the release dates the generator recorded when it injected the
 * disputes, so the forecast is scored against ground truth rather than against itself.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ForecastTest {

    private static final Path DATA_DIR = Path.of("..", "data");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static AnswerKey answerKey;

    @Autowired
    CsvIngestService ingestService;
    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    ForecastService forecastService;
    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void loadAnswerKey() throws IOException {
        answerKey = SyntheticDataWriter.answerKeyMapper()
                .readValue(DATA_DIR.resolve(SyntheticDataWriter.ANSWER_KEY_FILE).toFile(), AnswerKey.class);
    }

    @Test
    void theForecastMatchesTheReleaseDatesInTheAnswerKey() throws IOException {
        List<ForecastEntry> forecast = forecastService.forecast(committedBatch());
        List<AnswerKey.FutureSettlement> expected = answerKey.expectedFutureSettlements();

        assertThat(forecast).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            ForecastEntry actual = forecast.get(i);
            AnswerKey.FutureSettlement injected = expected.get(i);
            assertThat(actual.date()).isEqualTo(injected.date());
            assertThat(actual.expectedAmount()).isEqualByComparingTo(injected.expectedAmount());
            assertThat(actual.breakdownByMethod()).hasSameSizeAs(injected.breakdownByMethod());
            injected.breakdownByMethod().forEach((method, amount) ->
                    assertThat(actual.breakdownByMethod().get(method)).isEqualByComparingTo(amount));
        }
    }

    @Test
    void theCalendarRunsForwardFromTheLastSettlementTheReportCovers() throws IOException {
        List<ForecastEntry> forecast = forecastService.forecast(committedBatch());

        assertThat(forecast).isNotEmpty();
        assertThat(forecast).extracting(ForecastEntry::date).isSorted();
        assertThat(forecast).allSatisfy(entry ->
                assertThat(entry.date()).isAfter(answerKey.settlementCutoff()));
    }

    @Test
    void moneyOnTheCalendarIsMoneyComingOutOfADisputeHold() throws IOException {
        List<ForecastEntry> forecast = forecastService.forecast(committedBatch());

        assertThat(forecast).allSatisfy(entry -> {
            assertThat(entry.heldAmount()).isEqualByComparingTo(entry.expectedAmount());
            assertThat(entry.expectedAmount()).isPositive();
        });
    }

    @Test
    void aDisputeWithNoReleaseDateIsLeftOffTheCalendarRatherThanGuessedAt() throws IOException {
        long heldWithoutReleaseDate = answerKey.anomalies().stream()
                .filter(anomaly -> anomaly.type().name().equals("HELD_DISPUTE"))
                .filter(anomaly -> anomaly.expectedSettlementDate() == null)
                .count();
        long heldWithReleaseDate = answerKey.anomalies().stream()
                .filter(anomaly -> anomaly.type().name().equals("HELD_DISPUTE"))
                .filter(anomaly -> anomaly.expectedSettlementDate() != null)
                .count();

        List<ForecastEntry> forecast = forecastService.forecast(committedBatch());

        assertThat(heldWithoutReleaseDate).as("open and lost disputes have no release date").isPositive();
        assertThat(forecast).hasSize((int) heldWithReleaseDate);
    }

    @Test
    void aPaymentTooLateToHaveSettledYetAppearsOnItsNormalCycle() throws IOException {
        // Captured on Friday 2026-07-31 by card, so it settles T+2 business days on Tuesday 2026-08-04,
        // which is after the only settlement the report covers.
        String orders = """
                order_id,order_ts,amount,payment_id,method,payment_status,dispute_status,dispute_opened_at
                ORD-S1,2026-07-27T10:00:00,1000.00,pay_S1,UPI,CAPTURED,,
                ORD-S2,2026-07-31T18:00:00,5000.00,pay_S2,CARD,CAPTURED,,
                """;
        String settlement = """
                utr,settled_on,entity_type,entity_id,order_id,method,gross_amount,fee,gst,net_amount
                UTRAAA111111,2026-07-28,payment,pay_S1,ORD-S1,UPI,1000.00,0.00,0.00,1000.00
                """;
        String bank = """
                entry_date,description,utr,credit_amount
                2026-07-28,NEFT/UTRAAA111111/RAZORPAY,UTRAAA111111,1000.00
                """;

        UUID batchId = ingestService.ingest(stream(orders), stream(settlement), stream(bank)).batchId();
        reconciliationService.reconcile(batchId);

        List<ForecastEntry> forecast = forecastService.forecast(batchId);

        assertThat(forecast).singleElement().satisfies(entry -> {
            assertThat(entry.date()).isEqualTo(LocalDate.of(2026, 8, 4));
            // 5000.00 less 2% card fee and 18% GST on that fee.
            assertThat(entry.expectedAmount()).isEqualByComparingTo("4882.00");
            assertThat(entry.breakdownByMethod()).containsOnlyKeys("CARD");
            assertThat(entry.heldAmount()).as("nothing here is held").isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void theEndpointServesTheCalendar() throws Exception {
        UUID batchId = committedBatch();

        mockMvc.perform(get("/api/forecast/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(answerKey.expectedFutureSettlements().size()))
                .andExpect(jsonPath("$[0].date")
                        .value(answerKey.expectedFutureSettlements().get(0).date().toString()))
                .andExpect(jsonPath("$[0].breakdownByMethod").isNotEmpty())
                .andExpect(jsonPath("$[0].heldAmount").exists());

        mockMvc.perform(get("/api/forecast/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private UUID committedBatch() throws IOException {
        UUID batchId = ingestService.ingest(
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.ORDERS_FILE)),
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.SETTLEMENT_FILE)),
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.BANK_FILE))).batchId();
        reconciliationService.reconcile(batchId);
        return batchId;
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
