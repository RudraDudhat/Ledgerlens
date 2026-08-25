package com.ledgerlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "exceptions")
public class ExceptionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID batchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExceptionStatus status;

    @Column(nullable = false)
    private String entityRef;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExceptionOrigin origin;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> sourceRowIds;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public ExceptionStatus getStatus() { return status; }
    public void setStatus(ExceptionStatus status) { this.status = status; }
    public String getEntityRef() { return entityRef; }
    public void setEntityRef(String entityRef) { this.entityRef = entityRef; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public ExceptionOrigin getOrigin() { return origin; }
    public void setOrigin(ExceptionOrigin origin) { this.origin = origin; }
    public List<Long> getSourceRowIds() { return sourceRowIds; }
    public void setSourceRowIds(List<Long> sourceRowIds) { this.sourceRowIds = sourceRowIds; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
