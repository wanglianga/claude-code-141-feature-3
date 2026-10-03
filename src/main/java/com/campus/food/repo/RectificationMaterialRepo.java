package com.campus.food.repo;

import com.campus.food.domain.RectificationMaterial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RectificationMaterialRepo extends JpaRepository<RectificationMaterial, Long> {

    List<RectificationMaterial> findByAssessmentIdOrderBySubmittedAtDesc(Long assessmentId);
}
