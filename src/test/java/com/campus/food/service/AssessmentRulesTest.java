package com.campus.food.service;

import com.campus.food.domain.PurchasePlanItem;
import com.campus.food.domain.enums.AnomalyType;
import com.campus.food.domain.enums.IngredientCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssessmentRulesTest {

    @Test
    void triggersAtThreeQualifyingAnomaliesWithinWindow() {
        assertFalse(AssessmentRules.meetsTrigger(2));
        assertTrue(AssessmentRules.meetsTrigger(3));
        assertTrue(AssessmentRules.meetsTrigger(5));
    }

    @Test
    void missingDocumentsQualifiesForAnyCategory() {
        assertTrue(AssessmentRules.qualifies(AnomalyType.MISSING_DOCUMENTS, IngredientCategory.MEAT));
        assertTrue(AssessmentRules.qualifies(AnomalyType.MISSING_DOCUMENTS, IngredientCategory.VEGETABLE));
    }

    @Test
    void onlyVegetableRotQualifies() {
        assertTrue(AssessmentRules.qualifies(AnomalyType.HIGH_ROT_RATIO, IngredientCategory.VEGETABLE));
        // 水果腐烂与肉类腐烂不计入"蔬菜腐烂"连续考核
        assertFalse(AssessmentRules.qualifies(AnomalyType.HIGH_ROT_RATIO, IngredientCategory.FRUIT));
        assertFalse(AssessmentRules.qualifies(AnomalyType.HIGH_ROT_RATIO, IngredientCategory.MEAT));
        // 其他异常类型不计入
        assertFalse(AssessmentRules.qualifies(AnomalyType.LATE_DELIVERY, IngredientCategory.VEGETABLE));
        assertFalse(AssessmentRules.qualifies(AnomalyType.SHORT_DELIVERY, IngredientCategory.VEGETABLE));
    }

    @Test
    void enhancedThresholdIsStricterThanRoutine() {
        // 常规蔬菜阈值 10%，重点验收 5%；0.07 在常规下合格、重点验收超标
        assertEquals(new BigDecimal("0.05"),
                AssessmentRules.enhancedRotThreshold(IngredientCategory.VEGETABLE));
        assertTrue(AssessmentRules.enhancedRotExceeds(IngredientCategory.VEGETABLE, new BigDecimal("0.07")));
        assertFalse(AssessmentRules.enhancedRotExceeds(IngredientCategory.VEGETABLE, new BigDecimal("0.05")));
        // 其他品类重点阈值 3%
        assertEquals(new BigDecimal("0.03"),
                AssessmentRules.enhancedRotThreshold(IngredientCategory.MEAT));
        assertTrue(AssessmentRules.enhancedRotExceeds(IngredientCategory.MEAT, new BigDecimal("0.04")));
    }

    @Test
    void requiresConsecutivePasses() {
        assertFalse(AssessmentRules.passesRequirement(2, 3));
        assertTrue(AssessmentRules.passesRequirement(3, 3));
        assertTrue(AssessmentRules.passesRequirement(4, 3));
    }

    @Test
    void planItemShrinksByPurchaseRatioAndKeepsOriginal() {
        PurchasePlanItem item = new PurchasePlanItem();
        item.setPlannedQty(new BigDecimal("100.000"));
        item.applyPurchaseRatio(new BigDecimal("0.60"));
        // 原始量保留可追溯，计划量按 60% 缩减（向下取整到 3 位小数）
        assertEquals(new BigDecimal("100.000"), item.getOriginalPlannedQty());
        assertEquals(new BigDecimal("60.000"), item.getPlannedQty());
        assertEquals(0, item.getAppliedRatio().compareTo(new BigDecimal("0.60")));
    }
}
