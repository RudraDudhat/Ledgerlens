package com.ledgerlens.dto;

import java.util.List;

/**
 * An answer and the rows it stands on.
 *
 * @param citedRowIds the raw row ids, kept for the metrics and the audit trail
 * @param citations   the same rows named the way the merchant names them, for anyone reading
 * @param answerKind  FACTUAL when the answer came from this batch's rows, CONCEPTUAL when the
 *                    question asked what a term means and no row was consulted. Worth separating:
 *                    a definition with no citations is correct, while an answer about the batch
 *                    with no citations is not.
 */
public record AskResponse(String answer, List<Long> citedRowIds, List<Citation> citations, AnswerKind answerKind) {

    public enum AnswerKind {
        FACTUAL,
        CONCEPTUAL
    }

    public static AskResponse factual(String answer, List<Long> citedRowIds, List<Citation> citations) {
        return new AskResponse(answer, citedRowIds, citations, AnswerKind.FACTUAL);
    }

    public static AskResponse conceptual(String answer) {
        return new AskResponse(answer, List.of(), List.of(), AnswerKind.CONCEPTUAL);
    }
}
