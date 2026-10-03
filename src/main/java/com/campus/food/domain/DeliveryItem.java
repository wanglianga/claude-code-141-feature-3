package com.campus.food.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 送货明细（某批次中实际送达的食材行） */
@Entity
@Table(name = "delivery_items")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id")
    private DeliveryBatch batch;

    /** 对应的订货明细；临时替换/计划外送货时为 null */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_item_id")
    private OrderItem orderItem;

    /** 实际送达的食材（与订货明细不一致即为替换品类） */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal deliveredQty;

    /** 生产批次号 */
    private String productionBatchNo;

    private LocalDate productionDate;

    /** 保质期至 */
    private LocalDate expiryDate;

    /** 检疫票证号（肉类必需），为空表示票证缺失 */
    private String quarantineCertNo;

    /** 其他质量合格证明编号（蔬果农残检测报告/出库质检单等），蔬果重点验收时必需 */
    private String qualityCertNo;
}
