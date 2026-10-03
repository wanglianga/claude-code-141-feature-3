package com.campus.food.repo;

import com.campus.food.domain.AnomalyRecord;
import com.campus.food.domain.enums.AnomalyStatus;
import com.campus.food.domain.enums.AnomalyType;
import com.campus.food.domain.enums.CauseCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AnomalyRecordRepo extends JpaRepository<AnomalyRecord, Long> {
    List<AnomalyRecord> findBySupplierId(Long supplierId);
    List<AnomalyRecord> findBySupplierIdAndDetectedAtAfter(Long supplierId, LocalDateTime after);
    List<AnomalyRecord> findBySupplierIdAndDetectedAtBetween(Long supplierId, LocalDateTime from, LocalDateTime to);
    List<AnomalyRecord> findByStatus(AnomalyStatus status);
    List<AnomalyRecord> findByType(AnomalyType type);
    List<AnomalyRecord> findByBatchId(Long batchId);
    List<AnomalyRecord> findByCauseCategory(CauseCategory causeCategory);
    long countBySupplierIdAndDetectedAtAfter(Long supplierId, LocalDateTime after);

    /** 连续异常考核：供应商在时间窗内、指定类型集合的异常 */
    List<AnomalyRecord> findBySupplierIdAndTypeInAndDetectedAtBetween(
            Long supplierId, Collection<AnomalyType> types, LocalDateTime from, LocalDateTime to);
}
