package com.ledgerlens;

import com.ledgerlens.dto.AnswerKey;
import com.ledgerlens.dto.WaterfallStep;
import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.ReconciliationService;
import com.ledgerlens.service.SyntheticDataWriter;
import com.ledgerlens.service.WaterfallService;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The waterfall is only worth anything if it closes. Every assertion here is to the rupee against
 * the bank statement that was actually ingested, not against the numbers the waterfall produced.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class WaterfallTest {

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
    WaterfallService waterfallService;
    @Autowired
    BankEntryRepository bankEntryRepository;
    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void loadAnswerKey() throws IOException {
        answerKey = SyntheticDataWriter.answerKeyMapper()
                .readValue(DATA_DIR.resolve(SyntheticDataWriter.ANSWER_KEY_FILE).toFile(), AnswerKey.class);
    }

    @Test
    void theWaterfallSumsExactlyToTheBankCredits() throws IOException {
        UUID batchId = reconciledBatch();

        List<WaterfallStep> steps = waterfallService.waterfall(batchId);
        BigDecimal walked = steps.stream().map(WaterfallStep::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal bankCredits = bankEntryRepository.findByBatchIdOrderById(batchId).stream()
                .map(BankEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(walked).isEqualByComparingTo(bankCredits);
        assertThat(walked).isEqualByComparingTo(answerKey.totals().totalBankCredits());
    }

    @Test
    void nothingAboutTheSettlementTotalIsLeftUnexplained() throws IOException {
        List<WaterfallStep> steps = waterfallService.waterfall(reconciledBatch());

        assertThat(steps).extracting(WaterfallStep::label).doesNotContain(WaterfallService.UNEXPLAINED);
    }

    @Test
    void everyStepMatchesWhatWasInjectedIntoTheBatch() throws IOException {
        Map<String, BigDecimal> steps = stepsByLabel(waterfallService.waterfall(reconciledBatch()));
        AnswerKey.Totals expected = answerKey.totals();

        assertThat(steps.get(WaterfallService.GROSS_SALES)).isEqualByComparingTo(expected.grossSales());
        assertThat(steps.get(WaterfallService.FAILED_PAYMENTS)).isEqualByComparingTo(expected.failedAmount().negate());
        assertThat(steps.get(WaterfallService.FEES)).isEqualByComparingTo(expected.totalFees().negate());
        assertThat(steps.get(WaterfallService.GST)).isEqualByComparingTo(expected.totalGst().negate());
        assertThat(steps.get(WaterfallService.HELD)).isEqualByComparingTo(expected.heldNet().negate());
        assertThat(steps.get(WaterfallService.REFUNDS)).isEqualByComparingTo(expected.refundsTotal().negate());
        assertThat(steps.get(WaterfallService.NOT_CREDITED))
                .isEqualByComparingTo(expected.bankMissingTotal().negate());
        assertThat(steps.get(WaterfallService.UNMATCHED_CREDITS))
                .isEqualByComparingTo(expected.bankDuplicateTotal());
        assertThat(steps.get(WaterfallService.AMOUNT_DIFFERENCES))
                .isEqualByComparingTo(expected.bankMismatchDelta());
    }

    @Test
    void theStepsThatMoveMoneyCiteTheRowsTheyCameFrom() throws IOException {
        List<WaterfallStep> steps = waterfallService.waterfall(reconciledBatch());

        assertThat(steps).allSatisfy(step -> {
            assertThat(step.label()).isNotBlank();
            assertThat(step.amount()).isNotNull();
            if (step.amount().signum() != 0) {
                assertThat(step.sourceRowIds()).as("rows behind %s", step.label()).isNotEmpty();
            }
        });
        assertThat(stepsByLabel(steps)).containsOnlyKeys(
                WaterfallService.GROSS_SALES, WaterfallService.FAILED_PAYMENTS, WaterfallService.FEES,
                WaterfallService.GST, WaterfallService.HELD, WaterfallService.REFUNDS,
                WaterfallService.NOT_CREDITED, WaterfallService.UNMATCHED_CREDITS,
                WaterfallService.AMOUNT_DIFFERENCES);
    }

    @Test
    void theEndpointReturnsTheStepsInOrder() throws Exception {
        UUID batchId = reconciledBatch();

        mockMvc.perform(get("/api/reconcile/" + batchId + "/waterfall"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value(WaterfallService.GROSS_SALES))
                .andExpect(jsonPath("$[0].sourceRowIds.length()").value(answerKey.count()))
                .andExpect(jsonPath("$.length()").value(9));

        mockMvc.perform(get("/api/reconcile/" + UUID.randomUUID() + "/waterfall"))
                .andExpect(status().isNotFound());
    }

    private UUID reconciledBatch() throws IOException {
        UUID batchId = ingestService.ingest(
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.ORDERS_FILE)),
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.SETTLEMENT_FILE)),
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.BANK_FILE))).batchId();
        reconciliationService.reconcile(batchId);
        return batchId;
    }

    private static Map<String, BigDecimal> stepsByLabel(List<WaterfallStep> steps) {
        return steps.stream().collect(Collectors.toMap(WaterfallStep::label, WaterfallStep::amount,
                (first, second) -> first, java.util.LinkedHashMap::new));
    }
}
