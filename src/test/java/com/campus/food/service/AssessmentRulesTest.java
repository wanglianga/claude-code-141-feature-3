package com.campus.food.service;

import com.campus.food.domain.enums.AnomalyType;
import com.campus.food.domain.enums.IngredientCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssessmentRulesTest {

    @Test
    void triggersWhenBothTicketAndVegetableRotOccurWithinThreeWeeks() {
        // 票证缺失 1 次 + 蔬菜腐烂 1 次 -> 触发
        assertTrue(AssessmentRules.shouldTrigger(1, 1));
        // 多次腐烂 + 票证缺失
        assertTrue(AssessmentRules.shouldTrigger(1, 3));
        assertTrue(AssessmentRules.shouldTrigger(2, 2));
    }

    @Test
    void doesNotTriggerWhenOnlyOneKindOccurs() {
        // 仅票证缺失（无蔬菜腐烂）
        assertFalse(AssessmentRules.shouldTrigger(3, 0));
        // 仅蔬菜腐烂（无票证缺失）
        assertFalse(AssessmentRules.shouldTrigger(0, 3));
        assertFalse(AssessmentRules.shouldTrigger(0, 0));
    }

    @Test
    void strictThresholdIsTighter() {
        // 常规：蔬果 10%，其他 5%
        assertEquals(new BigDecimal("0.10"),
                AssessmentRules.rotThreshold(IngredientCategory.VEGETABLE, false));
        assertEquals(new BigDecimal("0.05"),
                AssessmentRules.rotThreshold(IngredientCategory.MEAT, false));
        // 整改期重点验收：蔬果 5%，其他 3%
        assertEquals(new BigDecimal("0.05"),
                AssessmentRules.rotThreshold(IngredientCategory.VEGETABLE, true));
        assertEquals(new BigDecimal("0.03"),
                AssessmentRules.rotThreshold(IngredientCategory.MEAT, true));
    }

    @Test
    void observesOnlyTicketMissingAndVegetableRot() {
        assertTrue(AssessmentRules.isObserved(AnomalyType.MISSING_DOCUMENTS, IngredientCategory.MEAT));
        assertTrue(AssessmentRules.isObserved(AnomalyType.MISSING_DOCUMENTS, IngredientCategory.VEGETABLE));
        assertTrue(AssessmentRules.isObserved(AnomalyType.HIGH_ROT_RATIO, IngredientCategory.VEGETABLE));
        // 水果/肉类腐烂不计入"蔬菜腐烂"
        assertFalse(AssessmentRules.isObserved(AnomalyType.HIGH_ROT_RATIO, IngredientCategory.FRUIT));
        assertFalse(AssessmentRules.isObserved(AnomalyType.HIGH_ROT_RATIO, IngredientCategory.MEAT));
        // 其他异常类型不计入连续异常考核
        assertFalse(AssessmentRules.isObserved(AnomalyType.LATE_DELIVERY, null));
        assertFalse(AssessmentRules.isObserved(AnomalyType.SHORT_DELIVERY, IngredientCategory.VEGETABLE));
    }
}
