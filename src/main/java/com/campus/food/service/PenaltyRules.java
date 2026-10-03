package com.campus.food.service;

import com.campus.food.domain.enums.AnomalyType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 合同扣罚规则（按异常类型对基数金额计罚）：
 * 晚送：基数 5%，最低 200；冷链超标：基数 5%，最低 300；
 * 少送：少送金额 10%；腐烂超标：腐烂金额 5%；票证缺失：固定 100；替换品类：送货金额 10%。
 * 连续异常考核事件本身不直接计罚（处置措施为整改/暂停/降比例）。
 */
public final class PenaltyRules {

    private static final BigDecimal LATE_MIN = new BigDecimal("200");
    private static final BigDecimal COLD_MIN = new BigDecimal("300");
    private static final BigDecimal MISSING_DOC_FLAT = new BigDecimal("100");

    private PenaltyRules() {
    }

    public static BigDecimal penaltyFor(AnomalyType type, BigDecimal baseAmount) {
        BigDecimal base = baseAmount == null ? BigDecimal.ZERO : baseAmount;
        BigDecimal result = switch (type) {
            case LATE_DELIVERY -> max(LATE_MIN, pct(base, "0.05"));
            case COLD_CHAIN_BREACH -> max(COLD_MIN, pct(base, "0.05"));
            case SHORT_DELIVERY -> pct(base, "0.10");
            case HIGH_ROT_RATIO -> pct(base, "0.05");
            case MISSING_DOCUMENTS -> MISSING_DOC_FLAT;
            case SUBSTITUTION -> pct(base, "0.10");
            case CANNOT_REJECT_PREPARED, SETTLEMENT_MISMATCH, RECURRING_ASSESSMENT -> BigDecimal.ZERO;
        };
        return result.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal base, String rate) {
        return base.multiply(new BigDecimal(rate));
    }

    private static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
