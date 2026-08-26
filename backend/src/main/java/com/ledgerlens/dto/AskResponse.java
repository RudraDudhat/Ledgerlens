package com.ledgerlens.dto;

import java.util.List;

/**
 * An answer grounded in retrieved rows. An empty citedRowIds means nothing in the batch supported an
 * answer, which the answer itself says plainly rather than papering over.
 */
public record AskResponse(String answer, List<Long> citedRowIds) {
}
