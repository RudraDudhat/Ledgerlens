package com.ledgerlens.service;

import com.ledgerlens.dto.BatchAnomaly;
import com.ledgerlens.dto.BatchProfile;
import com.ledgerlens.dto.SyntheticDataset;
import com.ledgerlens.dto.SyntheticDataset.OrderRow;
import com.ledgerlens.entity.PaymentMethod;
import com.ledgerlens.entity.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run of weeks is only useful to the monitor if the ordinary ones really are ordinary and the bad
 * one really is worse, so both halves of that are measured here rather than assumed.
 */
class GeneratorWeeksTest {

    private static final int COUNT = 300;
    private static final long SEED = 42L;
    private static final int WEEKS = 4;
    private static final BigDecimal DEGRADED_CARD_FEE = new BigDecimal("0.025");

    private final SyntheticDataGenerator generator = new SyntheticDataGenerator();

    /** Mirrors what the runner does, so the test grades the same series the CLI writes. */
    private List<SyntheticDataset> weeks() {
        int badWeek = WEEKS - 1;
        List<SyntheticDataset> generated = new ArrayList<>();
        for (int week = 1; week <= WEEKS; week++) {
            BatchProfile profile = BatchProfile.weekly(
                    SyntheticDataGenerator.WINDOW_START.plusDays((long) (week - 1) * 7));
            if (week == badWeek) {
                profile = profile.degraded(DEGRADED_CARD_FEE, 3.0);
            }
            generated.add(generator.generate(COUNT, SEED + week - 1, profile));
        }
        return generated;
    }

    @Test
    void everyWeekIsItsOwnBatchCoveringSevenConsecutiveDays() {
        List<SyntheticDataset> weeks = weeks();

        assertThat(weeks).hasSize(WEEKS);
        for (int index = 0; index < WEEKS; index++) {
            LocalDate expectedStart = SyntheticDataGenerator.WINDOW_START.plusDays(index * 7L);
            assertThat(weeks.get(index).answerKey().windowStart()).isEqualTo(expectedStart);
            assertThat(weeks.get(index).answerKey().windowEnd()).isEqualTo(expectedStart.plusDays(6));
            assertThat(weeks.get(index).orders()).hasSize(COUNT);
        }
    }

    @Test
    void exactlyOneWeekIsFlaggedAsDegraded() {
        List<SyntheticDataset> weeks = weeks();

        List<Integer> flagged = new ArrayList<>();
        for (int index = 0; index < WEEKS; index++) {
            if (!weeks.get(index).answerKey().batchAnomalies().isEmpty()) {
                flagged.add(index + 1);
            }
        }

        assertThat(flagged).containsExactly(WEEKS - 1);
    }

    @Test
    void theBadWeekRecordsEveryDegradationUnderTheNameTheDetectorUses() {
        List<String> metrics = weeks().get(WEEKS - 2).answerKey().batchAnomalies().stream()
                .map(BatchAnomaly::metric)
                .toList();

        assertThat(metrics).containsExactlyInAnyOrder(
                "fee_rate", "dispute_rate", "failure_rate_hour_02", "failure_rate_hour_03");
    }

    @Test
    void theBadWeekChargesMoreForCards() {
        List<SyntheticDataset> weeks = weeks();

        BigDecimal normal = cardFeeRate(weeks.get(0));
        BigDecimal degraded = cardFeeRate(weeks.get(WEEKS - 2));

        assertThat(normal).isEqualByComparingTo("0.020");
        assertThat(degraded).isEqualByComparingTo("0.025");
    }

    @Test
    void theBadWeekOpensThreeTimesAsManyDisputes() {
        List<SyntheticDataset> weeks = weeks();

        long normal = disputes(weeks.get(0));
        long degraded = disputes(weeks.get(WEEKS - 2));

        assertThat(degraded).isEqualTo(normal * 3);
    }

    @Test
    void theBadWeekFailsUpiPaymentsInTheSmallHoursAndOnlyThere() {
        List<SyntheticDataset> weeks = weeks();

        BigDecimal normalNight = nightUpiFailureRate(weeks.get(0));
        BigDecimal degradedNight = nightUpiFailureRate(weeks.get(WEEKS - 2));
        BigDecimal degradedDay = dayUpiFailureRate(weeks.get(WEEKS - 2));

        // The baseline is the ordinary 5% failure selection; the injection lifts the night far above it.
        assertThat(degradedNight).isGreaterThan(normalNight.multiply(new BigDecimal("3")));
        assertThat(degradedNight).isGreaterThan(degradedDay.multiply(new BigDecimal("3")));
    }

    @Test
    void weeklyBatchesTradeAroundTheClockSoTheHourBucketsMeanSomething() {
        SyntheticDataset week = weeks().get(0);

        long distinctHours = week.orders().stream().map(order -> order.orderTs().getHour()).distinct().count();

        assertThat(distinctHours).as("a batch confined to office hours cannot show a nightly outage").isEqualTo(24);
    }

    @Test
    void theSameSeedAndProfileProduceTheSameWeek() {
        BatchProfile profile = BatchProfile.weekly(SyntheticDataGenerator.WINDOW_START);

        SyntheticDataset first = generator.generate(COUNT, SEED, profile);
        SyntheticDataset second = generator.generate(COUNT, SEED, profile);

        assertThat(first.orders()).isEqualTo(second.orders());
        assertThat(first.settlementRows()).isEqualTo(second.settlementRows());
        assertThat(first.bankRows()).isEqualTo(second.bankRows());
    }

    private static BigDecimal cardFeeRate(SyntheticDataset week) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        for (SyntheticDataset.SettlementRow row : week.settlementRows()) {
            if (row.method() == PaymentMethod.CARD && row.entityType().equals("payment")) {
                gross = gross.add(row.grossAmount());
                fees = fees.add(row.fee());
            }
        }
        return fees.divide(gross, 3, java.math.RoundingMode.HALF_UP);
    }

    private static long disputes(SyntheticDataset week) {
        return week.orders().stream().filter(order -> order.disputeStatus() != null).count();
    }

    private static BigDecimal nightUpiFailureRate(SyntheticDataset week) {
        return upiFailureRate(week, true);
    }

    private static BigDecimal dayUpiFailureRate(SyntheticDataset week) {
        return upiFailureRate(week, false);
    }

    private static BigDecimal upiFailureRate(SyntheticDataset week, boolean atNight) {
        long attempts = 0;
        long failures = 0;
        for (OrderRow order : week.orders()) {
            int hour = order.orderTs().getHour();
            boolean night = hour >= SyntheticDataGenerator.NIGHT_FROM_HOUR
                    && hour < SyntheticDataGenerator.NIGHT_TO_HOUR;
            if (order.method() != PaymentMethod.UPI || night != atNight) {
                continue;
            }
            attempts++;
            if (order.paymentStatus() == PaymentStatus.FAILED) {
                failures++;
            }
        }
        return attempts == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(failures).divide(BigDecimal.valueOf(attempts), 4, java.math.RoundingMode.HALF_UP);
    }
}
