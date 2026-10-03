package com.campus.food.service;

import com.campus.food.domain.enums.AnomalyType;
import com.campus.food.domain.enums.IngredientCategory;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 供应商连续异常考核规则（纯逻辑，便于单测）：
 * 三周（21 天）内票证缺失与蔬果腐烂异常累计达到阈值次数，即触发考核；
 * 整改观察期执行更严格的腐烂比例阈值，连续通过指定次数后恢复正常供货。
 */
public final class AssessmentRules {

    /** 考核统计窗口：三周 */
    public static final int WINDOW_DAYS = 21;

    /** 窗口内触发考核的最少异常次数（"多次"） */
    public static final int TRIGGER_THRESHOLD = 3;

    /** 整改观察期内要求重点验收连续通过的次数（"后续几次送货"） */
    public static final int REQUIRED_CONSECUTIVE_PASSES = 3;

    /** 重点验收蔬果腐烂阈值（常规 10%，重点 5%） */
    private static final BigDecimal ENHANCED_ROT_VEG = new BigDecimal("0.05");

    /** 重点验收其他品类腐烂阈值（常规 5%，重点 3%） */
    private static final BigDecimal ENHANCED_ROT_OTHER = new BigDecimal("0.03");

    /** 纳入连续异常考核的异常类型：票证缺失 + 腐烂超标 */
    public static final Set<AnomalyType> QUALIFYING_TYPES =
            Set.of(AnomalyType.MISSING_DOCUMENTS, AnomalyType.HIGH_ROT_RATIO);

    private AssessmentRules() {
    }

    public static boolean qualifies(AnomalyType type) {
        return QUALIFYING_TYPES.contains(type);
    }

    /**
     * 是否计入连续异常考核：票证缺失（任意品类，如肉类检疫票/蔬果质检证明）
     * 或蔬菜腐烂超标（需求明确为"蔬菜腐烂"，水果不计入）。
     */
    public static boolean qualifies(AnomalyType type, IngredientCategory category) {
        if (type == AnomalyType.MISSING_DOCUMENTS) {
            return true;
        }
        return type == AnomalyType.HIGH_ROT_RATIO && category == IngredientCategory.VEGETABLE;
    }

    /** 窗口内命中异常数是否达到考核阈值 */
    public static boolean meetsTrigger(int qualifyingCount) {
        return qualifyingCount >= TRIGGER_THRESHOLD;
    }

    /** 重点验收腐烂比例阈值 */
    public static BigDecimal enhancedRotThreshold(IngredientCategory category) {
        return category == IngredientCategory.VEGETABLE || category == IngredientCategory.FRUIT
                ? ENHANCED_ROT_VEG : ENHANCED_ROT_OTHER;
    }

    /** 重点验收是否按更严阈值判定腐烂超标 */
    public static boolean enhancedRotExceeds(IngredientCategory category, BigDecimal rotRatio) {
        return rotRatio != null && rotRatio.compareTo(enhancedRotThreshold(category)) > 0;
    }

    /** 本次通过后是否已满足"连续通过 N 次"的恢复条件 */
    public static boolean passesRequirement(int consecutivePassesAfter, int requiredPasses) {
        return consecutivePassesAfter >= requiredPasses;
    }
}
