package com.campus.food.domain.enums;

/** 异常类型 */
public enum AnomalyType {
    SHORT_DELIVERY,            // 少送
    LATE_DELIVERY,             // 晚送
    COLD_CHAIN_BREACH,         // 冷链温度超标
    HIGH_ROT_RATIO,            // 腐烂比例过高
    MISSING_DOCUMENTS,         // 票证缺失（检疫票等）
    SUBSTITUTION,              // 临时替换品类
    CANNOT_REJECT_PREPARED,    // 食堂已备餐无法拒收
    SETTLEMENT_MISMATCH,       // 结算金额与验收重量不一致
    RECURRING_ASSESSMENT       // 连续异常考核（三周内多次票证缺失/蔬菜腐烂，系统自动生成）
}
