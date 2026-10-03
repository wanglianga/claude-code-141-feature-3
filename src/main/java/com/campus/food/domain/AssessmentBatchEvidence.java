package com.campus.food.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 考核事件汇总的异常批次证据（多行）：
 * 记录异常批次、验收照片、该批次退货金额及命中的异常类型。
 */
@Entity
@Table(name = "assessment_batch_evidence")
@Getter
@Setter
@NoArgsConstructor
public class AssessmentBatchEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "assessment_id")
    @JsonIgnore
    private SupplierAssessment assessment;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id")
    private DeliveryBatch batch;

    /** 批次号（冗余，便于展示） */
    @Column(nullable = false, length = 40)
    private String batchNo;

    /** 命中的异常类型，逗号分隔，如 MISSING_DOCUMENTS,HIGH_ROT_RATIO */
    @Column(length = 256)
    private String anomalyTypes;

    /** 验收照片 URL（逗号分隔，来自该批次验收记录） */
    @Column(length = 2048)
    private String photoUrls;

    /** 该批次退货金额合计 */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal returnAmount = BigDecimal.ZERO;
}
