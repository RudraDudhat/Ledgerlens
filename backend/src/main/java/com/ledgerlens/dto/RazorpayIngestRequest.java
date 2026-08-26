package com.ledgerlens.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** The window of test-mode activity to pull, inclusive of both days. */
public record RazorpayIngestRequest(@NotNull LocalDate from, @NotNull LocalDate to) {
}
