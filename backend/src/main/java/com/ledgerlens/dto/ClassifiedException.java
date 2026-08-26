package com.ledgerlens.dto;

/**
 * What the classifier decided about a record the rules could not explain.
 *
 * @param status     one of the reconciliation statuses, or UNKNOWN when the evidence does not support one
 * @param reason     at most two sentences, naming the rows that led there
 * @param confidence how strongly the candidate rows support the status, from 0.0 to 1.0. Not money,
 *                   so a double is fine here; it is stored as a scaled decimal.
 */
public record ClassifiedException(String status, String reason, double confidence) {
}
