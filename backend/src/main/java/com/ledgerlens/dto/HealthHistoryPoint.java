package com.ledgerlens.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** One point on a sparkline: an earlier batch's metrics, oldest first. */
public record HealthHistoryPoint(UUID batchId, LocalDateTime computedAt, BatchMetrics metrics) {
}
