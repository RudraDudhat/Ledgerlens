package com.ledgerlens;

import com.ledgerlens.dto.AnswerKey;
import com.ledgerlens.dto.CalibrationBucket;
import com.ledgerlens.dto.ExceptionView;
import com.ledgerlens.dto.MetricsReport;
import com.ledgerlens.dto.ReconcileSummary;
import com.ledgerlens.entity.ExceptionStatus;
import com.ledgerlens.repository.ExceptionRecordRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.MetricsService;
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
 * Scores the rule-based exception detector against the injected anomalies.
 *
 * <p>The scores here come out perfect, and that is a statement about the data rather than about
 * reconciliation being solved: every anomaly this generator injects is directly observable in the
 * three files, so a correct rule finds all of them. The number worth watching is UNKNOWN — anything
 * the rules cannot explain lands there, and it staying at zero is what makes the rest meaningful.
 */
@Testcontainers
@SpringBootTest(properties = "ledgerlens.answer-key-path=../data/answer_key.json")
@AutoConfigureMockMvc
class ExceptionMetricsTest {

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
    MetricsService metricsService;
    @Autowired
    IngestBatchRepository ingestBatchRepository;
    @Autowired
    ExceptionRecordRepository exceptionRepository;
    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void loadAnswerKey() throws IOException {
        answerKey = SyntheticDataWriter.answerKeyMapper()
                .readValue(DATA_DIR.resolve(SyntheticDataWriter.ANSWER_KEY_FILE).toFile(), AnswerKey.class);
    }

    @Test
    void everyInjectedAnomalyIsDetectedAndNothingElseIs() throws IOException {
        List<ExceptionView> exceptions = reconciliationService.exceptions(reconciledBatch());

        Map<String, Long> byStatus = exceptions.stream()
                .collect(Collectors.groupingBy(ExceptionView::status, Collectors.counting()));

        assertThat(byStatus).containsEntry(ExceptionStatus.PAYMENT_FAILED.name(), countOf(ExceptionStatus.PAYMENT_FAILED));
        assertThat(byStatus).containsEntry(ExceptionStatus.HELD_DISPUTE.name(), countOf(ExceptionStatus.HELD_DISPUTE));
        assertThat(byStatus).containsEntry(ExceptionStatus.REFUND_PRIOR_CYCLE.name(), countOf(ExceptionStatus.REFUND_PRIOR_CYCLE));
        assertThat(byStatus).containsEntry(ExceptionStatus.BANK_MISSING.name(), countOf(ExceptionStatus.BANK_MISSING));
        assertThat(byStatus).containsEntry(ExceptionStatus.BANK_DUPLICATE.name(), countOf(ExceptionStatus.BANK_DUPLICATE));
        assertThat(byStatus).containsEntry(ExceptionStatus.AMOUNT_MISMATCH.name(), countOf(ExceptionStatus.AMOUNT_MISMATCH));
        assertThat(byStatus).doesNotContainKey(ExceptionStatus.UNKNOWN.name());
        assertThat(exceptions).hasSize(answerKey.anomalies().size());
    }

    @Test
    void everyExceptionCarriesAReasonAConfidenceAndTheRowsBehindIt() throws IOException {
        List<ExceptionView> exceptions = reconciliationService.exceptions(reconciledBatch());

        assertThat(exceptions).allSatisfy(exception -> {
            assertThat(exception.reason()).isNotBlank();
            assertThat(exception.entityRef()).isNotBlank();
            assertThat(exception.origin()).isEqualTo("RULE");
            assertThat(exception.sourceRowIds()).isNotEmpty();
            assertThat(exception.confidence()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        });
    }

    @Test
    void inferredFindingsAreLessConfidentThanObservedOnes() throws IOException {
        List<ExceptionView> exceptions = reconciliationService.exceptions(reconciledBatch());

        BigDecimal failedConfidence = confidenceOf(exceptions, ExceptionStatus.PAYMENT_FAILED);
        BigDecimal missingConfidence = confidenceOf(exceptions, ExceptionStatus.BANK_MISSING);

        assertThat(failedConfidence).isEqualByComparingTo("1.000");
        assertThat(missingConfidence).isLessThan(failedConfidence);
    }

    @Test
    void precisionAndRecallAreScoredAgainstTheAnswerKey() throws IOException {
        MetricsReport report = metricsService.metrics(reconciledBatch());

        assertThat(report.answerKeyPresent()).isTrue();
        assertThat(report.expectedCount()).isEqualTo(answerKey.anomalies().size());
        assertThat(report.detectedCount()).isEqualTo(answerKey.anomalies().size());

        for (ExceptionStatus status : List.of(ExceptionStatus.PAYMENT_FAILED, ExceptionStatus.HELD_DISPUTE,
                ExceptionStatus.REFUND_PRIOR_CYCLE, ExceptionStatus.BANK_MISSING,
                ExceptionStatus.BANK_DUPLICATE, ExceptionStatus.AMOUNT_MISMATCH)) {
            MetricsReport.TypeMetrics metrics = report.byType().get(status.name());
            assertThat(metrics.falsePositives()).as("false positives for %s", status).isZero();
            assertThat(metrics.falseNegatives()).as("false negatives for %s", status).isZero();
            assertThat(metrics.precision()).as("precision for %s", status).isEqualByComparingTo("1.0000");
            assertThat(metrics.recall()).as("recall for %s", status).isEqualByComparingTo("1.0000");
        }
        assertThat(report.overall().truePositives()).isEqualTo(answerKey.anomalies().size());
        assertThat(report.overall().falsePositives()).isZero();
        assertThat(report.overall().falseNegatives()).isZero();

        System.out.printf("exceptions: %d detected, %d injected, overall precision %s recall %s%n",
                report.detectedCount(), report.expectedCount(),
                report.overall().precision(), report.overall().recall());
    }

    @Test
    void aFindingAgainstTheWrongRecordWouldCostBothPrecisionAndRecall() throws IOException {
        MetricsReport report = metricsService.metrics(reconciledBatch());

        // Types are joined on the entity reference, so the counts above are per record, not per type.
        MetricsReport.TypeMetrics failed = report.byType().get(ExceptionStatus.PAYMENT_FAILED.name());
        assertThat(failed.truePositives()).isEqualTo((int) countOf(ExceptionStatus.PAYMENT_FAILED));
    }

    @Test
    void withoutAnAnswerKeyTheReportSaysSoRatherThanInventingScores() throws IOException {
        UUID batchId = reconciledBatch();
        MetricsService withoutKey =
                new MetricsService(ingestBatchRepository, exceptionRepository, "no/such/answer_key.json");

        MetricsReport report = withoutKey.metrics(batchId);

        assertThat(report.answerKeyPresent()).isFalse();
        assertThat(report.byType()).isEmpty();
        assertThat(report.overall()).isNull();
        assertThat(report.detectedCount()).isEqualTo(answerKey.anomalies().size());
    }

    @Test
    void theSummaryReportsCountsByStatus() throws IOException {
        UUID batchId = reconciledBatch();

        ReconcileSummary summary = reconciliationService.summary(batchId);

        assertThat(summary.countsByStatus())
                .containsEntry(ExceptionStatus.MATCHED.name(), summary.matchedOrderCount())
                .containsEntry(ExceptionStatus.PAYMENT_FAILED.name(), (int) countOf(ExceptionStatus.PAYMENT_FAILED));
    }

    @Test
    void reRunningReconcileReplacesRatherThanAccumulatesExceptions() throws IOException {
        UUID batchId = reconciledBatch();
        int afterFirst = reconciliationService.exceptions(batchId).size();

        reconciliationService.reconcile(batchId);

        assertThat(reconciliationService.exceptions(batchId)).hasSize(afterFirst);
    }

    @Test
    void theEndpointsServeExceptionsAndMetrics() throws Exception {
        UUID batchId = reconciledBatch();

        mockMvc.perform(get("/api/reconcile/" + batchId + "/exceptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(answerKey.anomalies().size()))
                .andExpect(jsonPath("$[0].reason").isNotEmpty())
                .andExpect(jsonPath("$[0].confidence").exists());

        mockMvc.perform(get("/api/metrics/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerKeyPresent").value(true))
                .andExpect(jsonPath("$.overall.precision").value(1.0))
                .andExpect(jsonPath("$.byType.PAYMENT_FAILED.recall").value(1.0));

        mockMvc.perform(get("/api/metrics/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void calibrationBucketsShowWhetherStatedConfidenceIsEarned() throws IOException {
        MetricsReport report = metricsService.metrics(reconciledBatch());

        assertThat(report.calibration()).hasSize(4);
        assertThat(report.calibration()).allSatisfy(bucket -> {
            assertThat(bucket.correct()).isLessThanOrEqualTo(bucket.count());
            if (bucket.count() == 0) {
                assertThat(bucket.observedAccuracy()).isNull();
            } else {
                assertThat(bucket.observedAccuracy()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
                assertThat(bucket.meanConfidence()).isBetween(bucket.lowerBound(), bucket.upperBound());
            }
        });
        assertThat(report.calibration().stream().mapToInt(CalibrationBucket::count).sum())
                .as("every finding lands in exactly one bucket")
                .isEqualTo(report.detectedCount());

        report.calibration().stream().filter(bucket -> bucket.count() > 0).forEach(bucket ->
                System.out.printf("calibration [%s,%s] n=%d meanConfidence=%s observedAccuracy=%s%n",
                        bucket.lowerBound(), bucket.upperBound(), bucket.count(),
                        bucket.meanConfidence(), bucket.observedAccuracy()));
    }

    private UUID reconciledBatch() throws IOException {
        UUID batchId = ingestService.ingest(
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.ORDERS_FILE)),
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.SETTLEMENT_FILE)),
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.BANK_FILE))).batchId();
        reconciliationService.reconcile(batchId);
        return batchId;
    }

    private static long countOf(ExceptionStatus type) {
        return answerKey.anomalies().stream().filter(anomaly -> anomaly.type() == type).count();
    }

    private static BigDecimal confidenceOf(List<ExceptionView> exceptions, ExceptionStatus status) {
        return exceptions.stream()
                .filter(exception -> exception.status().equals(status.name()))
                .map(ExceptionView::confidence)
                .findFirst()
                .orElseThrow();
    }
}
