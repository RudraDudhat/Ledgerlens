package com.ledgerlens.dto;

import java.util.UUID;

/** What an ingest produced: the batch id every later call needs, plus what landed in each table. */
public record IngestResponse(
        UUID batchId,
        int orders,
        int payments,
        int disputes,
        int settlementBatches,
        int settlementLines,
        int bankEntries) {
}
