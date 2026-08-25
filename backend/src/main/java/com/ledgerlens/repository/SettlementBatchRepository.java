package com.ledgerlens.repository;

import com.ledgerlens.entity.SettlementBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {

    List<SettlementBatch> findByBatchIdOrderBySettledOn(UUID batchId);

    long countByBatchId(UUID batchId);
}
