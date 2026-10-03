package com.campus.food.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 采购计划明细 */
@Entity
@Table(name = "purchase_plan_items")
@Getter
@Setter
@NoArgsConstructor
public class PurchasePlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id")
    private PurchasePlan plan;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    /** 菜单需求量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal requiredQty;

    /** 生成计划时的库存量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal stockQty;

    /** 计划采购量 = 需求量 - 可用库存（库存需保留安全线） */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal plannedQty;

    /** 建议供应商 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    /** 预估单价（取食材参考价） */
    @Column(precision = 14, scale = 2)
    private BigDecimal estimatedPrice;

    /** 生成计划时实际应用的供应商采购比例（供应商被考核降低采购比例时 < 1） */
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal appliedRatio = BigDecimal.ONE;

    /** 按采购比例缩减前的原始计划采购量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal originalPlannedQty = BigDecimal.ZERO;

    /**
     * 按供应商采购比例缩减计划量（向下取整到 3 位小数，保留可追溯的原始量与应用比例）。
     */
    public void applyPurchaseRatio(BigDecimal ratio) {
        if (originalPlannedQty == null || originalPlannedQty.signum() == 0) {
            originalPlannedQty = plannedQty;
        }
        appliedRatio = ratio == null ? BigDecimal.ONE : ratio;
        plannedQty = originalPlannedQty.multiply(appliedRatio).setScale(3, RoundingMode.DOWN);
    }
}
