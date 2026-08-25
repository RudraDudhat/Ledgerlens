package com.ledgerlens.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "settlement_lines")
public class SettlementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID batchId;

    @Column(nullable = false)
    private Long settlementBatchRowId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementLineType lineType;

    @Column(nullable = false)
    private String entityId;

    private String orderId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public Long getSettlementBatchRowId() { return settlementBatchRowId; }
    public void setSettlementBatchRowId(Long settlementBatchRowId) { this.settlementBatchRowId = settlementBatchRowId; }
    public SettlementLineType getLineType() { return lineType; }
    public void setLineType(SettlementLineType lineType) { this.lineType = lineType; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
