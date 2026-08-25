package com.ledgerlens.generator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Writes a synthetic batch to disk:
 * {@code --spring.profiles.active=generate --count=300 --seed=42 --out=data/}.
 */
@Component
@Profile("generate")
public class GenerateRunner implements CommandLineRunner {

    @Value("${count:300}")
    private int count;

    @Value("${seed:42}")
    private long seed;

    @Value("${out:data/}")
    private String out;

    @Override
    public void run(String... args) throws IOException {
        long startedAt = System.nanoTime();
        SyntheticDataset dataset = new SyntheticDataGenerator().generate(count, seed);
        Path outDir = Path.of(out);
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
        System.out.printf("  gross sales %s, settled %s, bank credits %s%n",
                key.totals().grossSales(), key.totals().totalSettled(), key.totals().totalBankCredits());
        System.out.printf("  written to %s%n", outDir.toAbsolutePath());
    }
}
