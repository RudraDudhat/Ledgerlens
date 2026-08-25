package com.ledgerlens.repository;

import com.ledgerlens.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByBatchIdOrderById(UUID batchId);

    long countByBatchId(UUID batchId);
}
