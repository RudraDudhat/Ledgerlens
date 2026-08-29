package com.ledgerlens.dto;

import java.util.List;

/**
 * An answer and the rows it stands on.
 *
 * @param citedRowIds the raw row ids, kept for the metrics and the audit trail
 * @param citations   the same rows named the way the merchant names them, for anyone reading
 */
public record AskResponse(String answer, List<Long> citedRowIds, List<Citation> citations) {
}
