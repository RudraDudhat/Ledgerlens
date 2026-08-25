package com.ledgerlens.repository;

import com.ledgerlens.entity.IngestBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IngestBatchRepository extends JpaRepository<IngestBatch, UUID> {
}
