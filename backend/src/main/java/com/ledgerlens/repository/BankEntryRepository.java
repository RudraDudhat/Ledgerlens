package com.ledgerlens.repository;

import com.ledgerlens.entity.BankEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BankEntryRepository extends JpaRepository<BankEntry, Long> {

    List<BankEntry> findByBatchIdOrderById(UUID batchId);

    long countByBatchId(UUID batchId);
}
