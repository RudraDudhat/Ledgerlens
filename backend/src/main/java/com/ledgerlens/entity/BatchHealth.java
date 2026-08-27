package com.ledgerlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The vital signs of one batch, stored as jsonb because the shape grows: a new ratio should not cost
 * a migration, and nothing here is ever queried by an individual key.
 */
@Entity
@Table(name = "batch_health")
public class BatchHealth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID batchId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String metrics;

    @Column(nullable = false)
    private LocalDateTime computedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public String getMetrics() { return metrics; }
    public void setMetrics(String metrics) { this.metrics = metrics; }
    public LocalDateTime getComputedAt() { return computedAt; }
    public void setComputedAt(LocalDateTime computedAt) { this.computedAt = computedAt; }
}
