package com.campus.food.service;

import com.campus.food.domain.enums.AnomalyType;
import com.campus.food.domain.enums.IngredientCategory;

import java.math.BigDecimal;

/**
 * 供应商连续异常考核规则（纯函数，便于单测）：
 * 观察窗口 21 天（三周），窗口内同时出现票证缺失与蔬菜腐烂、且合计达到多次时触发考核；
 * 整改期执行更严格的腐烂比例阈值。
 */
public final class AssessmentRules {

    /** 观察窗口：三周 */
    public static final int WINDOW_DAYS = 21;

    /** 窗口内触发考核的最少异常次数（票证缺失 + 蔬菜腐烂合计） */
    public static final int TRIGGER_MIN_COUNT = 2;

    /** 整改期重点验收默认需连续通过次数 */
    public static final int DEFAULT_REQUIRED_PASSES = 3;

    private AssessmentRules() {
    }

    /** 是否应生成考核事件：三周内票证缺失与蔬菜腐烂均出现，且合计多次（>=2） */
    public static boolean shouldTrigger(int missingDocCount, int vegetableRotCount) {
        return missingDocCount >= 1 && vegetableRotCount >= 1
                && (missingDocCount + vegetableRotCount) >= TRIGGER_MIN_COUNT;
    }

    /**
     * 腐烂比例阈值。整改期更严格：蔬果由 10% 收紧到 5%，其他品类由 5% 收紧到 3%。
     */
    public static BigDecimal rotThreshold(IngredientCategory category, boolean strict) {
        if (strict) {
            return (category == IngredientCategory.VEGETABLE || category == IngredientCategory.FRUIT)
                    ? new BigDecimal("0.05") : new BigDecimal("0.03");
        }
        return (category == IngredientCategory.VEGETABLE || category == IngredientCategory.FRUIT)
                ? new BigDecimal("0.10") : new BigDecimal("0.05");
    }

    /** 该异常是否计入连续异常考核观察（仅票证缺失与蔬菜腐烂） */
    public static boolean isObserved(AnomalyType type, IngredientCategory category) {
        if (type == AnomalyType.MISSING_DOCUMENTS) {
            return true;
        }
        return type == AnomalyType.HIGH_ROT_RATIO && category == IngredientCategory.VEGETABLE;
    }
}
