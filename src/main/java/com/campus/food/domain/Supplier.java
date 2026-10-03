package com.campus.food.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** 供应商 */
@Entity
@Table(name = "suppliers")
@Getter
@Setter
@NoArgsConstructor
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 供应商编码，唯一 */
    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    private String contactPerson;

    private String phone;

    private String address;

    /** 是否具备冷链运输能力 */
    @Column(nullable = false)
    private boolean coldChainCapable = false;

    /** 可供品类，逗号分隔的 IngredientCategory 名 */
    @Column(length = 256)
    private String categories;

    /** 合同编号 */
    private String contractNo;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * 当前采购比例（0~1）：生成采购计划时按此比例缩减分配给该供应商的计划采购量。
     * 考核处置"降低下月采购比例"时下调；整改观察连续通过、恢复正常供货时重置为 1。
     */
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal purchaseRatio = BigDecimal.ONE;
}
