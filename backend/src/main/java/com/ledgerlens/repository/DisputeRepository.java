package com.ledgerlens.repository;

import com.ledgerlens.entity.Dispute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    List<Dispute> findByBatchIdOrderById(UUID batchId);

    long countByBatchId(UUID batchId);
}
