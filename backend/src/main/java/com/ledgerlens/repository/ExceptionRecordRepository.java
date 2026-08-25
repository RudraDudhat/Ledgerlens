package com.ledgerlens.repository;

import com.ledgerlens.entity.ExceptionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ExceptionRecordRepository extends JpaRepository<ExceptionRecord, Long> {

    List<ExceptionRecord> findByBatchIdOrderById(UUID batchId);

    long countByBatchId(UUID batchId);

    @Modifying
    @Query("delete from ExceptionRecord e where e.batchId = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);
}
