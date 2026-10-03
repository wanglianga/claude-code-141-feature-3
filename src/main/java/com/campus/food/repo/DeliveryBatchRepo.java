package com.campus.food.repo;

import com.campus.food.domain.DeliveryBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeliveryBatchRepo extends JpaRepository<DeliveryBatch, Long> {
    Optional<DeliveryBatch> findByBatchNo(String batchNo);
    List<DeliveryBatch> findByOrderId(Long orderId);
    List<DeliveryBatch> findByOrderSupplierIdAndArrivedAtBetween(Long supplierId,
                                                                LocalDateTime from, LocalDateTime to);
}
