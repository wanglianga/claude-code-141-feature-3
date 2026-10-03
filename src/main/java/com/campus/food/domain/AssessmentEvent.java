package com.campus.food.domain;

import com.campus.food.domain.enums.AssessmentDecision;
import com.campus.food.domain.enums.AssessmentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 供应商连续异常考核事件。
 * 同一供应商三周（21 天）内多次出现票证缺失、蔬菜腐烂异常时由系统自动生成。
 */
@Entity
@Table(name = "assessment_events")
@Getter
@Setter
@NoArgsConstructor
public class AssessmentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String eventNo;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AssessmentStatus status = AssessmentStatus.OPEN;

    @Column(nullable = false)
    private LocalDateTime triggeredAt = LocalDateTime.now();

    /** 统计窗口起点（含），触发时刻往前 21 天 */
    @Column(nullable = false)
    private LocalDateTime windowStart;

    /** 统计窗口终点（含），一般为触发时刻 */
    @Column(nullable = false)
    private LocalDateTime windowEnd;

    /** 窗口内票证缺失 + 蔬果腐烂异常条数 */
    @Column(nullable = false)
    private int qualifyingAnomalyCount;

    /** 窗口覆盖的送货批次 id（逗号分隔，证据快照） */
    @Column(length = 512)
    private String coveredBatchIds;

    /** 命中的票证缺失/蔬果腐烂异常 id（逗号分隔，证据快照） */
    @Column(length = 1024)
    private String qualifyingAnomalyIds;

    /** 窗口内累计退货金额（证据快照） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalReturnAmount = BigDecimal.ZERO;

    /** 触发时对未来 7 天菜单的影响餐次数（证据快照） */
    @Column(nullable = false)
    private int impactedMealCount;

    @Column(length = 1024)
    private String summary;

    /** 对应的连续异常考核异常记录（RECURRING_ASSESSMENT） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "anomaly_id")
    private AnomalyRecord anomaly;

    // ---- 采购负责人处置 ----

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AssessmentDecision decision;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "decided_by")
    private AppUser decidedBy;

    private LocalDateTime decidedAt;

    @Column(length = 512)
    private String decisionNote;

    /** 整改截止日期（要求整改时填写） */
    private LocalDate rectificationDeadline;

    /** 降低后的下月采购比例（0~1，REDUCE_RATIO 时写入供应商档案） */
    @Column(precision = 5, scale = 4)
    private BigDecimal nextMonthPurchaseRatio;

    // ---- 整改观察（重点验收） ----

    private LocalDateTime enhancedStartedAt;

    /** 重点验收要求连续通过的次数 */
    @Column(nullable = false)
    private int requiredConsecutivePasses = 3;

    /** 整改观察期内已连续通过次数（任一不合格批次归零） */
    @Column(nullable = false)
    private int consecutivePasses = 0;

    /** 整改观察期内重点验收总次数 */
    @Column(nullable = false)
    private int enhancedInspectionCount = 0;

    private LocalDateTime resolvedAt;

    @Column(length = 512)
    private String resolutionNote;
}
