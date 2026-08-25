package com.ledgerlens.rules;

import com.ledgerlens.entity.DisputeStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * When money behind a dispute is expected back. A won dispute releases fourteen days after it was
 * opened; anything else has no release date at all, so the money stays out of settlement. Absence of
 * a release date is treated as still held, which is the safe reading for a finance tool.
 */
public final class DisputeHolds {

    public static final int RELEASE_DAYS = 14;

    private DisputeHolds() {
    }

    public static LocalDate expectedReleaseDate(DisputeStatus status, LocalDateTime openedAt) {
        return status == DisputeStatus.WON ? openedAt.toLocalDate().plusDays(RELEASE_DAYS) : null;
    }

    public static boolean isHeldAsOf(DisputeStatus status, LocalDateTime openedAt, LocalDate asOf) {
        LocalDate release = expectedReleaseDate(status, openedAt);
        return release == null || release.isAfter(asOf);
    }
}
