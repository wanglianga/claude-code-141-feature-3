package com.campus.food.domain;

import com.campus.food.domain.enums.AssessmentActionType;
import com.campus.food.domain.enums.AssessmentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商连续异常考核事件。
 * 同一供应商三周内多次出现票证缺失 / 蔬菜腐烂时由系统自动生成，
 * 汇总异常批次、验收照片、退货金额与对菜单影响，供采购负责人处置。
 */
@Entity
@Table(name = "supplier_assessments")
@Getter
@Setter
@NoArgsConstructor
public class SupplierAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String assessmentNo;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssessmentStatus status = AssessmentStatus.OPEN;

    /** 触发考核的观察窗口起点（含）/ 终点（含） */
    @Column(nullable = false)
    private LocalDateTime windowStart;

    @Column(nullable = false)
    private LocalDateTime windowEnd;

    /** 窗口内票证缺失异常次数 */
    @Column(nullable = false)
    private int missingDocCount;

    /** 窗口内蔬菜腐烂超标异常次数 */
    @Column(nullable = false)
    private int vegetableRotCount;

    /** 窗口内去重后的异常批次数 */
    @Column(nullable = false)
    private int abnormalBatchCount;

    /** 汇总退货金额（窗口内该供应商退货单应退金额合计） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal returnAmount = BigDecimal.ZERO;

    /** 受影响的未来餐次数（去重） */
    @Column(nullable = false)
    private int impactedMealCount;

    @Column(length = 512)
    private String summary;

    // ---- 采购负责人处置 ----

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private AssessmentActionType actionType;

    /** 处置说明 */
    @Column(length = 512)
    private String decisionNote;

    /** 降低采购比例时的目标比例（0~1，如 0.6 表示下月按 60% 采购） */
    @Column(precision = 5, scale = 4)
    private BigDecimal adjustedRatio;

    private LocalDateTime decidedAt;

    // ---- 供应商整改 ----

    /** 整改材料说明 */
    @Column(length = 1024)
    private String rectificationMaterial;

    /** 整改材料附件 URL（逗号分隔） */
    @Column(length = 1024)
    private String rectificationAttachments;

    private LocalDateTime rectifiedAt;

    // ---- 重点验收（整改期） ----

    /** 整改期需连续通过重点验收的次数 */
    @Column(nullable = false)
    private int requiredPasses = 3;

    /** 已连续通过的重点验收次数 */
    @Column(nullable = false)
    private int consecutivePasses = 0;

    /** 最近一次重点验收是否通过 */
    private Boolean lastCheckPassed;

    private LocalDateTime closedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
