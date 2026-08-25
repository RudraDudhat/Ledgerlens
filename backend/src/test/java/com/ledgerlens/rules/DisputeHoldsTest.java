package com.ledgerlens.rules;

import com.ledgerlens.entity.DisputeStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Release dates are worked out by hand: a dispute opened 18 Aug 2026 releases on 1 Sep 2026. */
class DisputeHoldsTest {

    private static final LocalDateTime OPENED = LocalDateTime.of(2026, 8, 18, 9, 30);
    private static final LocalDate RELEASE = LocalDate.of(2026, 9, 1);

    @Test
    void aWonDisputeReleasesFourteenDaysAfterItWasOpened() {
        assertThat(DisputeHolds.expectedReleaseDate(DisputeStatus.WON, OPENED)).isEqualTo(RELEASE);
    }

    @Test
    void anUnresolvedOrLostDisputeHasNoReleaseDate() {
        assertThat(DisputeHolds.expectedReleaseDate(DisputeStatus.OPEN, OPENED)).isNull();
        assertThat(DisputeHolds.expectedReleaseDate(DisputeStatus.LOST, OPENED)).isNull();
    }

    @Test
    void moneyStaysHeldRightUpToTheReleaseDate() {
        assertThat(DisputeHolds.isHeldAsOf(DisputeStatus.WON, OPENED, RELEASE.minusDays(1))).isTrue();
        assertThat(DisputeHolds.isHeldAsOf(DisputeStatus.WON, OPENED, RELEASE)).isFalse();
        assertThat(DisputeHolds.isHeldAsOf(DisputeStatus.WON, OPENED, RELEASE.plusDays(1))).isFalse();
    }

    @Test
    void withoutAReleaseDateTheMoneyIsTreatedAsStillHeld() {
        LocalDate longAfter = RELEASE.plusYears(1);
        assertThat(DisputeHolds.isHeldAsOf(DisputeStatus.OPEN, OPENED, longAfter)).isTrue();
        assertThat(DisputeHolds.isHeldAsOf(DisputeStatus.LOST, OPENED, longAfter)).isTrue();
    }
}
