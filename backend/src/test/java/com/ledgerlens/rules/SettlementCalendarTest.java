package com.ledgerlens.rules;

import com.ledgerlens.domain.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * All expected dates are worked out by hand from this calendar:
 * Mon 2026-07-27, Tue 28, Wed 29, Thu 30, Fri 31, Sat 2026-08-01, Sun 02, Mon 03, Tue 04.
 */
class SettlementCalendarTest {

    private static final LocalDate MON_JUL_27 = LocalDate.of(2026, 7, 27);
    private static final LocalDate THU_JUL_30 = LocalDate.of(2026, 7, 30);
    private static final LocalDate FRI_JUL_31 = LocalDate.of(2026, 7, 31);
    private static final LocalDate SAT_AUG_1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate MON_AUG_3 = LocalDate.of(2026, 8, 3);
    private static final LocalDate TUE_AUG_4 = LocalDate.of(2026, 8, 4);

    @Test
    void theCalendarUsedByTheOtherTestsIsTheRealOne() {
        assertThat(MON_JUL_27.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(FRI_JUL_31.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(SAT_AUG_1.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(MON_AUG_3.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void upiAndWalletSettleNextBusinessDay() {
        assertThat(SettlementCalendar.settlementDate(PaymentMethod.UPI, MON_JUL_27))
                .isEqualTo(LocalDate.of(2026, 7, 28));
        assertThat(SettlementCalendar.settlementDate(PaymentMethod.WALLET, MON_JUL_27))
                .isEqualTo(LocalDate.of(2026, 7, 28));
    }

    @Test
    void cardAndNetbankingSettleTwoBusinessDaysLater() {
        assertThat(SettlementCalendar.settlementDate(PaymentMethod.CARD, MON_JUL_27))
                .isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(SettlementCalendar.settlementDate(PaymentMethod.NETBANKING, MON_JUL_27))
                .isEqualTo(LocalDate.of(2026, 7, 29));
    }

    @Test
    void theWeekendIsSkipped() {
        assertThat(SettlementCalendar.settlementDate(PaymentMethod.UPI, FRI_JUL_31)).isEqualTo(MON_AUG_3);
        assertThat(SettlementCalendar.settlementDate(PaymentMethod.CARD, FRI_JUL_31)).isEqualTo(TUE_AUG_4);
        assertThat(SettlementCalendar.settlementDate(PaymentMethod.CARD, THU_JUL_30)).isEqualTo(MON_AUG_3);
    }

    @Test
    void aWeekendPaymentCountsFromTheFollowingMonday() {
        assertThat(SettlementCalendar.settlementDate(PaymentMethod.WALLET, SAT_AUG_1)).isEqualTo(MON_AUG_3);
        assertThat(SettlementCalendar.settlementDate(PaymentMethod.NETBANKING, SAT_AUG_1)).isEqualTo(TUE_AUG_4);
    }

    @Test
    void refundsHitTheBatchOnOrAfterTheDayTheyWereCreated() {
        assertThat(SettlementCalendar.refundSettlementDate(THU_JUL_30)).isEqualTo(THU_JUL_30);
        assertThat(SettlementCalendar.refundSettlementDate(SAT_AUG_1)).isEqualTo(MON_AUG_3);
    }

    @Test
    void weekendsAreNotBusinessDays() {
        assertThat(SettlementCalendar.isBusinessDay(FRI_JUL_31)).isTrue();
        assertThat(SettlementCalendar.isBusinessDay(SAT_AUG_1)).isFalse();
        assertThat(SettlementCalendar.isBusinessDay(LocalDate.of(2026, 8, 2))).isFalse();
        assertThat(SettlementCalendar.isBusinessDay(MON_AUG_3)).isTrue();
    }
}
