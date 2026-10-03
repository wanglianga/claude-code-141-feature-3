package com.campus.food.repo;

import com.campus.food.domain.SupplierAssessment;
import com.campus.food.domain.enums.AssessmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierAssessmentRepo extends JpaRepository<SupplierAssessment, Long> {

    List<SupplierAssessment> findBySupplierIdOrderByCreatedAtDesc(Long supplierId);

    List<SupplierAssessment> findByStatusOrderByCreatedAtDesc(AssessmentStatus status);

    List<SupplierAssessment> findBySupplierIdAndStatus(Long supplierId, AssessmentStatus status);

    /** 供应商当前生效（未关闭）的考核事件，取最新一条 */
    Optional<SupplierAssessment> findFirstBySupplierIdAndStatusInOrderByCreatedAtDesc(
            Long supplierId, List<AssessmentStatus> statuses);

    boolean existsBySupplierIdAndStatusIn(Long supplierId, List<AssessmentStatus> statuses);

    /** 该供应商最近一次考核事件（含已关闭），用于界定新一轮观察窗口 */
    Optional<SupplierAssessment> findFirstBySupplierIdOrderByCreatedAtDesc(Long supplierId);
}
