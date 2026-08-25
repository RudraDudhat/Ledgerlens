package com.ledgerlens.repository;

import com.ledgerlens.entity.MatchRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MatchRecordRepository extends JpaRepository<MatchRecord, Long> {

    List<MatchRecord> findByBatchIdOrderById(UUID batchId);

    Page<MatchRecord> findByBatchIdOrderById(UUID batchId, Pageable pageable);

    long countByBatchId(UUID batchId);

    @Modifying
    @Query("delete from MatchRecord m where m.batchId = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);
}
