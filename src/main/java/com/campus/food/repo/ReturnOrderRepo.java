package com.campus.food.repo;

import com.campus.food.domain.ReturnOrder;
import com.campus.food.domain.enums.ReturnStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReturnOrderRepo extends JpaRepository<ReturnOrder, Long> {
    List<ReturnOrder> findBySupplierId(Long supplierId);
    List<ReturnOrder> findByStatus(ReturnStatus status);
    List<ReturnOrder> findByAcceptanceItemRecordBatchId(Long batchId);
}
