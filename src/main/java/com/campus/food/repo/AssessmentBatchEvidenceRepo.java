package com.campus.food.repo;

import com.campus.food.domain.AssessmentBatchEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssessmentBatchEvidenceRepo extends JpaRepository<AssessmentBatchEvidence, Long> {

    List<AssessmentBatchEvidence> findByAssessmentId(Long assessmentId);

    List<AssessmentBatchEvidence> findByAssessmentIdOrderByIdAsc(Long assessmentId);

    void deleteByAssessmentId(Long assessmentId);
}
