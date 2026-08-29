package com.ledgerlens.dto;

import java.util.List;

/**
 * What the model is asked to return: the prose, and the ids of the rows it used.
 *
 * <p>Kept separate from {@link AskResponse} because that is the shape the API answers in, and it
 * carries the resolved rows behind those ids. Putting them on this record would put them in the
 * schema the model is handed and invite it to invent rows rather than cite the ones it was given.
 */
public record ModelAnswer(String answer, List<Long> citedRowIds) {
}
