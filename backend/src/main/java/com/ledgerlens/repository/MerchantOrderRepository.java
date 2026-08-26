package com.ledgerlens.repository;

import com.ledgerlens.entity.MerchantOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MerchantOrderRepository extends JpaRepository<MerchantOrder, Long> {

    List<MerchantOrder> findByBatchIdOrderById(UUID batchId);

    long countByBatchId(UUID batchId);

    List<MerchantOrder> findByBatchIdAndOrderId(UUID batchId, String orderId);

    List<MerchantOrder> findByBatchIdAndAmount(UUID batchId, java.math.BigDecimal amount);
}
