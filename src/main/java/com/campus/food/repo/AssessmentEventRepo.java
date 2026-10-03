package com.campus.food.repo;

import com.campus.food.domain.AssessmentEvent;
import com.campus.food.domain.enums.AssessmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AssessmentEventRepo extends JpaRepository<AssessmentEvent, Long> {

    List<AssessmentEvent> findBySupplierIdOrderByTriggeredAtDesc(Long supplierId);

    List<AssessmentEvent> findByStatusOrderByTriggeredAtDesc(AssessmentStatus status);

    List<AssessmentEvent> findAllByOrderByTriggeredAtDesc();

    /** 该供应商最近一个未终结的考核事件 */
    Optional<AssessmentEvent> findFirstBySupplierIdAndStatusInOrderByTriggeredAtDesc(
            Long supplierId, Collection<AssessmentStatus> statuses);

    boolean existsBySupplierIdAndStatusIn(Long supplierId, Collection<AssessmentStatus> statuses);
}
