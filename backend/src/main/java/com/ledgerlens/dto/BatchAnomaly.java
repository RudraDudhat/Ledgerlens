package com.ledgerlens.dto;

/**
 * A deliberate degradation applied to a whole batch, recorded so the monitor's alerts can be graded
 * against it. The metric name is exactly what the detector would emit, so the two can be joined.
 */
public record BatchAnomaly(String metric, String detail) {
}
