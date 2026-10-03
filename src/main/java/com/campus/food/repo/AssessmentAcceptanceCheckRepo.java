package com.campus.food.repo;

import com.campus.food.domain.AssessmentAcceptanceCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssessmentAcceptanceCheckRepo extends JpaRepository<AssessmentAcceptanceCheck, Long> {

    List<AssessmentAcceptanceCheck> findByAssessmentIdOrderByCheckedAtAsc(Long assessmentId);

    long countByAssessmentIdAndPassedTrue(Long assessmentId);
}
