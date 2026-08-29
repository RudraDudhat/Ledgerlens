package com.ledgerlens.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A row an answer was built from, named the way the merchant names it.
 *
 * <p>The id is a database key and means nothing to the person reading the answer: it identifies the
 * row for the retriever, not for a founder checking a claim. What makes a citation checkable is the
 * reference they already know — the order id on their own export, the UTR on their bank statement —
 * so that travels with it.
 *
 * @param kind   ORDER, SETTLEMENT, BANK_CREDIT or EXCEPTION: which of the three files it came from
 * @param ref    the order id or UTR, whichever names this row in the merchant's own records
 * @param note   the bank's narration, or an exception's status and reason; null when there is none
 */
public record Citation(
        Long id,
        String kind,
        String ref,
        BigDecimal amount,
        LocalDate date,
        String note) {
}
