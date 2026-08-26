package com.ledgerlens.repository;

import com.ledgerlens.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByBatchIdOrderById(UUID batchId);

    long countByBatchId(UUID batchId);
}
