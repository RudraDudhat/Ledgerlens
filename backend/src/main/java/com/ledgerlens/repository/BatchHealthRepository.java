package com.ledgerlens.repository;

import com.ledgerlens.entity.BatchHealth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BatchHealthRepository extends JpaRepository<BatchHealth, Long> {

    Optional<BatchHealth> findByBatchId(UUID batchId);

    List<BatchHealth> findAllByOrderByComputedAtAsc();

    void deleteByBatchId(UUID batchId);
}
