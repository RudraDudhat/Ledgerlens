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

/**
 * One metric that moved far enough from its own history to be worth a look. Every number on it is
 * computed by rules; only the cause and the suggested check come from the model, and both may be
 * null when no model is configured.
 */
@Entity
@Table(name = "anomaly_alerts")
public class AnomalyAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID batchId;

    @Column(nullable = false, length = 60)
    private String metric;

    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal currentValue;

    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal baselineValue;

    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal ratio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertSeverity severity;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> sourceRowIds;

    @Column(length = 500)
    private String likelyCause;

    @Column(length = 300)
    private String suggestedCheck;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal currentValue) { this.currentValue = currentValue; }
    public BigDecimal getBaselineValue() { return baselineValue; }
    public void setBaselineValue(BigDecimal baselineValue) { this.baselineValue = baselineValue; }
    public BigDecimal getRatio() { return ratio; }
    public void setRatio(BigDecimal ratio) { this.ratio = ratio; }
    public AlertSeverity getSeverity() { return severity; }
    public void setSeverity(AlertSeverity severity) { this.severity = severity; }
    public List<Long> getSourceRowIds() { return sourceRowIds; }
    public void setSourceRowIds(List<Long> sourceRowIds) { this.sourceRowIds = sourceRowIds; }
    public String getLikelyCause() { return likelyCause; }
    public void setLikelyCause(String likelyCause) { this.likelyCause = likelyCause; }
    public String getSuggestedCheck() { return suggestedCheck; }
    public void setSuggestedCheck(String suggestedCheck) { this.suggestedCheck = suggestedCheck; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
