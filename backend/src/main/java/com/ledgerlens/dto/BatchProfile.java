package com.ledgerlens.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * How one generated batch should behave. The default is the month-long batch committed in data/,
 * and its values must not change: the committed files, the README figures and the byte-identical
 * determinism test are all pinned to them.
 *
 * @param cardFeeRateOverride a card fee rate the gateway did not actually agree to, or null
 * @param nightUpiFailures    force UPI failures between 02:00 and 04:00, the shape of an overnight outage
 * @param disputeMultiplier   1.0 for an ordinary batch, higher for a bad one
 */
public record BatchProfile(
        LocalDate windowStart,
        int windowDays,
        int disputeWindowDays,
        int refundMaxDayOffset,
        int earliestHour,
        int latestHour,
        BigDecimal cardFeeRateOverride,
        boolean nightUpiFailures,
        double disputeMultiplier) {

    /** Orders between 08:00 and 20:00 over a month — the shape of the committed sample batch. */
    public static BatchProfile monthly(LocalDate windowStart) {
        return new BatchProfile(windowStart, 31, 10, 20, 8, 20, null, false, 1.0);
    }

    /** A single week, trading at all hours so the hour buckets have something to measure. */
    public static BatchProfile weekly(LocalDate windowStart) {
        return new BatchProfile(windowStart, 7, 3, 2, 0, 23, null, false, 1.0);
    }

    /** The week something went wrong: dearer card fees, a nightly UPI outage, triple the disputes. */
    public BatchProfile degraded(BigDecimal cardFeeRate, double disputeMultiplier) {
        return new BatchProfile(windowStart, windowDays, disputeWindowDays, refundMaxDayOffset,
                earliestHour, latestHour, cardFeeRate, true, disputeMultiplier);
    }

    public int hourSpread() {
        return latestHour - earliestHour + 1;
    }
}
