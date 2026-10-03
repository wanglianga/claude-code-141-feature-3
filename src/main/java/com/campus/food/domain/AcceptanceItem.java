package com.campus.food.domain;

import com.campus.food.domain.enums.AcceptanceItemResult;
import com.campus.food.domain.enums.DowngradeStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** 验收明细（按送货行逐项判定） */
@Entity
@Table(name = "acceptance_items")
@Getter
@Setter
@NoArgsConstructor
public class AcceptanceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "record_id")
    private AcceptanceRecord record;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "delivery_item_id")
    private DeliveryItem deliveryItem;

    /** 冗余食材，便于统计 */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    /** 订货数量（计划外送货为 0） */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal orderedQty = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal deliveredQty;

    /** 合格入库数量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal acceptedQty = BigDecimal.ZERO;

    /** 拒收数量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal rejectedQty = BigDecimal.ZERO;

    /** 降级使用数量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal downgradedQty = BigDecimal.ZERO;

    /** 腐烂/损耗比例 0~1 */
    @Column(precision = 5, scale = 4)
    private BigDecimal rotRatio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AcceptanceItemResult result;

    @Column(length = 512)
    private String rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DowngradeStatus downgradeStatus = DowngradeStatus.NONE;

    /** 降级折价率（0.2 表示按 8 折结算） */
    @Column(precision = 5, scale = 4)
    private BigDecimal discountRate;

    /** 逐项验收照片标识（称重/腐烂/票证特写等，逗号分隔）；重点验收每项必传 */
    @Column(length = 1024)
    private String photos;
}
