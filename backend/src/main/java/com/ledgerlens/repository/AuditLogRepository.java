package com.ledgerlens.repository;

import com.ledgerlens.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByBatchIdOrderById(UUID batchId);
}
