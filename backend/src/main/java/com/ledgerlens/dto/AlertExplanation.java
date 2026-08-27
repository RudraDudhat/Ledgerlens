package com.ledgerlens.dto;

/** What the model may add to an alert. It never supplies or adjusts a number. */
public record AlertExplanation(String likelyCause, String suggestedCheck, double confidence) {
}
