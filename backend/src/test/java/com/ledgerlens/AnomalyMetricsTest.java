package com.ledgerlens;

import com.ledgerlens.dto.AlertView;
import com.ledgerlens.dto.AnswerKey;
import com.ledgerlens.dto.BatchAnomaly;
import com.ledgerlens.dto.BatchProfile;
import com.ledgerlens.dto.MetricsReport;
import com.ledgerlens.repository.AnomalyAlertRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.HealthService;
import com.ledgerlens.service.MetricsService;
import com.ledgerlens.service.ReconciliationService;
import com.ledgerlens.service.SyntheticDataGenerator;
import com.ledgerlens.service.SyntheticDataWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates four weeks, reconciles them in order, and grades the alerts raised for the degraded week
 * against the degradations the generator recorded.
 *
 * <p>The result is deliberately not perfect and the assertions say so. A card fee rate lifted from
 * 2.0% to 2.5% moves the blended fee rate by about a tenth, nowhere near the doubling the specified
 * threshold requires, so fee_rate is injected and never raised. That is a real limitation of a
 * fixed-ratio rule on a blended metric, and it belongs in the numbers rather than in a comment.
 */
@Testcontainers
@SpringBootTest
class AnomalyMetricsTest {

    private static final int COUNT = 300;
    private static final long SEED = 42L;
    private static final int WEEKS = 4;
    private static final int BAD_WEEK = WEEKS - 1;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @TempDir
    Path tmp;

    @Autowired
    CsvIngestService ingestService;
    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    HealthService healthService;
    @Autowired
    IngestBatchRepository ingestBatchRepository;
    @Autowired
    AnomalyAlertRepository alertRepository;

    private final List<UUID> batchIds = new ArrayList<>();
    private Path badWeekAnswerKey;

    @BeforeEach
    void generateAndReconcileFourWeeks() throws IOException {
        batchIds.clear();
        SyntheticDataGenerator generator = new SyntheticDataGenerator();
        SyntheticDataWriter writer = new SyntheticDataWriter();

        for (int week = 1; week <= WEEKS; week++) {
            BatchProfile profile = BatchProfile.weekly(
                    SyntheticDataGenerator.WINDOW_START.plusDays((long) (week - 1) * 7));
            if (week == BAD_WEEK) {
                profile = profile.degraded(new BigDecimal("0.025"), 3.0);
            }
            Path weekDir = tmp.resolve("week-%02d".formatted(week));
            writer.write(weekDir, generator.generate(COUNT, SEED + week - 1, profile));
            if (week == BAD_WEEK) {
                badWeekAnswerKey = weekDir.resolve(SyntheticDataWriter.ANSWER_KEY_FILE);
            }

            // Ingested in order, so each week is only ever judged against the weeks before it.
            UUID batchId = ingestService.ingest(
                    Files.newInputStream(weekDir.resolve(SyntheticDataWriter.ORDERS_FILE)),
                    Files.newInputStream(weekDir.resolve(SyntheticDataWriter.SETTLEMENT_FILE)),
                    Files.newInputStream(weekDir.resolve(SyntheticDataWriter.BANK_FILE))).batchId();
            reconciliationService.reconcile(batchId);
            batchIds.add(batchId);
        }
    }

    @Test
    void theEarlyWeeksRaiseNothingBecauseThereIsNothingToCompareThemWith() {
        assertThat(healthService.report(batchIds.get(0)).alerts()).isEmpty();
        assertThat(healthService.report(batchIds.get(1)).alerts()).isEmpty();
    }

    @Test
    void theDegradedWeekIsTheOneThatRaisesAlarms() {
        List<AlertView> badWeek = healthService.report(batchIds.get(BAD_WEEK - 1)).alerts();
        List<AlertView> goodWeek = healthService.report(batchIds.get(WEEKS - 1)).alerts();

        assertThat(badWeek).as("the week that went wrong").isNotEmpty();
        assertThat(badWeek.size()).isGreaterThan(goodWeek.size());
    }

    @Test
    void theTrippledDisputeRateIsCaught() {
        List<AlertView> alerts = healthService.report(batchIds.get(BAD_WEEK - 1)).alerts();

        AlertView disputes = alerts.stream()
                .filter(alert -> alert.metric().equals("dispute_rate"))
                .findFirst()
                .orElse(null);

        assertThat(disputes).as("three times the disputes is above the doubling threshold").isNotNull();
        assertThat(disputes.ratio()).isGreaterThan(new BigDecimal("2"));
        assertThat(disputes.sourceRowIds()).isNotEmpty().hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void alertsAreScoredAgainstWhatWasDeliberatelyBroken() throws IOException {
        UUID degraded = batchIds.get(BAD_WEEK - 1);
        MetricsService scoped = new MetricsService(ingestBatchRepository, null, alertRepository,
                badWeekAnswerKey.toString());

        AnswerKey key = SyntheticDataWriter.answerKeyMapper()
                .readValue(badWeekAnswerKey.toFile(), AnswerKey.class);
        List<String> injected = key.batchAnomalies().stream().map(BatchAnomaly::metric).toList();
        List<String> raised = healthService.report(degraded).alerts().stream().map(AlertView::metric).toList();

        long hits = injected.stream().filter(raised::contains).count();

        assertThat(injected).isNotEmpty();
        assertThat(hits).as("at least the dispute spike must be found").isPositive();

        System.out.printf("alerts: injected %s%n         raised   %s%n         caught %d of %d%n",
                injected, raised, hits, injected.size());
        assertThat(scoped).isNotNull();
    }

    @Test
    void aBlendedFeeRateCannotDoubleFromOneMethodGettingDearer() {
        List<AlertView> alerts = healthService.report(batchIds.get(BAD_WEEK - 1)).alerts();

        boolean feeRateRaised = alerts.stream().anyMatch(alert -> alert.metric().equals("fee_rate"));

        // Cards are a quarter of the mix, so 2.0% to 2.5% moves the blend by roughly a tenth. The
        // rule asks for a doubling, so this injected fault is a known miss rather than a surprise.
        assertThat(feeRateRaised)
                .as("a fixed ratio on a blended metric cannot see a single method get dearer")
                .isFalse();
    }
}
