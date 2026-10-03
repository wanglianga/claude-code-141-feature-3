package com.campus.food.domain.enums;

/**
 * 验收严格级别。
 * NORMAL   常规验收
 * ENHANCED 重点验收（整改观察期内供应商每次送货强制执行：逐项拍照、加倍抽检、
 *          票证齐全、外观/温度合格、执行更严格腐烂阈值）
 */
public enum InspectionLevel {
    NORMAL,
    ENHANCED
}
