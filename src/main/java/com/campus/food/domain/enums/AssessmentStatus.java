package com.campus.food.domain.enums;

/** 供应商考核事件状态 */
public enum AssessmentStatus {
    OPEN,            // 已生成考核事件，待采购负责人处理
    RECTIFYING,      // 采购要求整改，等待供应商提交整改材料
    UNDER_REVIEW,    // 已提交整改材料，后续送货进入重点验收
    SUSPENDED,       // 暂停供货
    RATIO_REDUCED,   // 降低下月采购比例（同时进入整改/重点验收）
    CLOSED           // 连续通过重点验收，恢复正常供货比例，考核关闭
}
