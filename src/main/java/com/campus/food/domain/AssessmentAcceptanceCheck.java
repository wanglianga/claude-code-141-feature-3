package com.campus.food.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 整改期重点验收记录：供应商提交整改材料后，后续每次送货进入重点验收，
 * 连续通过达到要求次数后，采购负责人方可恢复正常供货比例。
 */
@Entity
@Table(name = "assessment_acceptance_checks")
@Getter
@Setter
@NoArgsConstructor
public class AssessmentAcceptanceCheck {

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

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "acceptance_record_id")
    private AcceptanceRecord acceptanceRecord;

    /** 重点验收（更严格标准）是否通过 */
    @Column(nullable = false)
    private boolean passed;

    /** 截至本次连续通过次数 */
    @Column(nullable = false)
    private int consecutivePasses;

    @Column(length = 512)
    private String note;

    @Column(nullable = false)
    private LocalDateTime checkedAt = LocalDateTime.now();
}
