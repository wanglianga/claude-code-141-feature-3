package com.campus.food.domain.enums;

/**
 * 供应商连续异常考核事件状态。
 *
 * OPEN            系统自动生成，等待采购负责人处置
 * RECTIFICATION   采购负责人要求限期整改：等待供应商提交整改材料
 * MATERIAL_SUBMITTED 供应商已提交整改材料，等待采购负责人确认并进入重点验收观察期
 * ENHANCED_OBSERVATION 整改观察期：该供应商每次送货执行更严格验收（重点验收），连续通过 N 次后恢复
 * SUSPENDED       采购负责人暂停供货（可后续放行进入整改观察期）
 * RESOLVED        考核关闭：整改观察连续通过，恢复正常供货比例
 * CLOSED          采购负责人关闭考核（不进入整改流程的情形）
 */
public enum AssessmentStatus {
    OPEN,
    RECTIFICATION,
    MATERIAL_SUBMITTED,
    ENHANCED_OBSERVATION,
    SUSPENDED,
    RESOLVED,
    CLOSED
}
