package com.ledgerlens.repository;

import com.ledgerlens.entity.SettlementLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SettlementLineRepository extends JpaRepository<SettlementLine, Long> {

    List<SettlementLine> findByBatchIdOrderById(UUID batchId);

    long countByBatchId(UUID batchId);
}
