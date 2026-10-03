package com.campus.food.domain.enums;

/**
 * 采购负责人对考核事件的处置决定。
 * RECTIFY       要求整改（供应商交材料后，后续送货进入重点验收）
 * SUSPEND       暂停供货
 * REDUCE_RATIO  降低下月采购比例
 */
public enum AssessmentDecision {
    RECTIFY,
    SUSPEND,
    REDUCE_RATIO
}
