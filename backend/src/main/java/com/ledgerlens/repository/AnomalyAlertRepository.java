package com.ledgerlens.repository;

import com.ledgerlens.entity.AnomalyAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AnomalyAlertRepository extends JpaRepository<AnomalyAlert, Long> {

    List<AnomalyAlert> findByBatchIdOrderById(UUID batchId);

    @Modifying
    @Query("delete from AnomalyAlert a where a.batchId = :batchId")
    void deleteByBatchId(@Param("batchId") UUID batchId);
}
