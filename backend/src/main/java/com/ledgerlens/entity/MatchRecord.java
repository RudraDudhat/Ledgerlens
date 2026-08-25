package com.ledgerlens.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "matches")
public class MatchRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID batchId;

    @Column(nullable = false)
    private String matchType;

    private Long orderRowId;

    private Long settlementLineRowId;

    private Long settlementBatchRowId;

    private Long bankEntryRowId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public String getMatchType() { return matchType; }
    public void setMatchType(String matchType) { this.matchType = matchType; }
    public Long getOrderRowId() { return orderRowId; }
    public void setOrderRowId(Long orderRowId) { this.orderRowId = orderRowId; }
    public Long getSettlementLineRowId() { return settlementLineRowId; }
    public void setSettlementLineRowId(Long settlementLineRowId) { this.settlementLineRowId = settlementLineRowId; }
    public Long getSettlementBatchRowId() { return settlementBatchRowId; }
    public void setSettlementBatchRowId(Long settlementBatchRowId) { this.settlementBatchRowId = settlementBatchRowId; }
    public Long getBankEntryRowId() { return bankEntryRowId; }
    public void setBankEntryRowId(Long bankEntryRowId) { this.bankEntryRowId = bankEntryRowId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
