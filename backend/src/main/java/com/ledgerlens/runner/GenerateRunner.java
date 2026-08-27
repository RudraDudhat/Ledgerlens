package com.ledgerlens.runner;

import com.ledgerlens.dto.AnswerKey;
import com.ledgerlens.dto.BatchProfile;
import com.ledgerlens.dto.SyntheticDataset;
import com.ledgerlens.service.SyntheticDataGenerator;
import com.ledgerlens.service.SyntheticDataWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Writes a synthetic batch to disk:
 * {@code --spring.profiles.active=generate --count=300 --seed=42 --out=data/}.
 *
 * <p>With {@code --weeks=N} it writes N consecutive weekly batches into {@code out/weeks/week-01/}
 * onwards instead, which is what the health monitor needs: a baseline can only be built from a run
 * of comparable batches. Each week derives its own seed from the given one, so the whole series is
 * reproducible from a single number.
 */
@Component
@Profile("generate")
public class GenerateRunner implements CommandLineRunner {

    /** Fees the gateway did not agree to, in the one week that goes wrong. */
    private static final BigDecimal DEGRADED_CARD_FEE_RATE = new BigDecimal("0.025");
    private static final double DEGRADED_DISPUTE_MULTIPLIER = 3.0;
    private static final int DAYS_PER_WEEK = 7;
    private static final int MIN_WEEKS_FOR_A_BAD_ONE = 3;

    @Value("${count:300}")
    private int count;

    @Value("${seed:42}")
    private long seed;

    @Value("${out:data/}")
    private String out;

    @Value("${weeks:1}")
    private int weeks;

    @Override
    public void run(String... args) throws IOException {
        long startedAt = System.nanoTime();
        Path outDir = Path.of(out);

        if (weeks <= 1) {
            writeOne(outDir, count, seed, BatchProfile.monthly(SyntheticDataGenerator.WINDOW_START), startedAt);
            return;
        }

        // With three or more weeks one of them goes wrong, so the monitor has something to find.
        int badWeek = weeks >= MIN_WEEKS_FOR_A_BAD_ONE ? weeks - 1 : 0;
        for (int week = 1; week <= weeks; week++) {
            BatchProfile profile = BatchProfile.weekly(
                    SyntheticDataGenerator.WINDOW_START.plusDays((long) (week - 1) * DAYS_PER_WEEK));
            if (week == badWeek) {
                profile = profile.degraded(DEGRADED_CARD_FEE_RATE, DEGRADED_DISPUTE_MULTIPLIER);
            }
            Path weekDir = outDir.resolve("weeks").resolve("week-%02d".formatted(week));
            writeOne(weekDir, count, seed + week - 1, profile, System.nanoTime());
        }

        System.out.printf("%d weekly batches written under %s (week %d degraded)%n",
                weeks, outDir.resolve("weeks").toAbsolutePath(), badWeek);
    }

    private void writeOne(Path outDir, int count, long seed, BatchProfile profile, long startedAt) throws IOException {
        SyntheticDataset dataset = new SyntheticDataGenerator().generate(count, seed, profile);
        new SyntheticDataWriter().write(outDir, dataset);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        AnswerKey key = dataset.answerKey();
        System.out.printf("generated %d orders, seed %d, window %s..%s in %d ms%n",
                count, seed, key.windowStart(), key.windowEnd(), elapsed.toMillis());
        System.out.printf("  %s: %d rows%n", SyntheticDataWriter.ORDERS_FILE, dataset.orders().size());
        System.out.printf("  %s: %d rows%n", SyntheticDataWriter.SETTLEMENT_FILE, dataset.settlementRows().size());
        System.out.printf("  %s: %d rows%n", SyntheticDataWriter.BANK_FILE, dataset.bankRows().size());
        System.out.printf("  %s: %d anomalies %s%n",
                SyntheticDataWriter.ANSWER_KEY_FILE, key.anomalies().size(), key.anomalyCounts());
        if (!key.batchAnomalies().isEmpty()) {
            System.out.printf("  degraded: %s%n", key.batchAnomalies().stream().map(a -> a.metric()).toList());
        }
        System.out.printf("  gross sales %s, settled %s, bank credits %s%n",
                key.totals().grossSales(), key.totals().totalSettled(), key.totals().totalBankCredits());
        System.out.printf("  written to %s%n", outDir.toAbsolutePath());
    }
}
