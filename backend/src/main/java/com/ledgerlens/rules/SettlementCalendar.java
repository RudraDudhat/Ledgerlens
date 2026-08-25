package com.ledgerlens.rules;

import com.ledgerlens.entity.PaymentMethod;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Settlement cycle dates. Saturday and Sunday are not business days; public holidays are ignored.
 * A refund is deducted from the batch that lands on the first business day on or after the day the
 * refund was created, which is often a later cycle than the one the original payment settled in.
 */
public final class SettlementCalendar {

    private SettlementCalendar() {
    }

    public static int settlementLagDays(PaymentMethod method) {
        return switch (method) {
            case UPI, WALLET -> 1;
            case CARD, NETBANKING -> 2;
        };
    }

    public static LocalDate settlementDate(PaymentMethod method, LocalDate paymentDate) {
        return plusBusinessDays(paymentDate, settlementLagDays(method));
    }

    public static LocalDate refundSettlementDate(LocalDate refundCreatedDate) {
        return nextBusinessDayOnOrAfter(refundCreatedDate);
    }

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    public static LocalDate nextBusinessDayOnOrAfter(LocalDate date) {
        LocalDate d = date;
        while (!isBusinessDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    public static LocalDate plusBusinessDays(LocalDate date, int businessDays) {
        LocalDate d = date;
        for (int i = 0; i < businessDays; i++) {
            do {
                d = d.plusDays(1);
            } while (!isBusinessDay(d));
        }
        return d;
    }
}
