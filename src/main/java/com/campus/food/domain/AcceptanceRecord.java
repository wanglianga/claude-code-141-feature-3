package com.campus.food.domain;

import com.campus.food.domain.enums.AcceptanceOverallResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 验收记录（一个送货批次一次验收） */
@Entity
@Table(name = "acceptance_records")
@Getter
@Setter
@NoArgsConstructor
public class AcceptanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String recordNo;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id")
    private DeliveryBatch batch;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "inspector_id")
    private AppUser inspector;

    @Column(nullable = false)
    private LocalDateTime inspectedAt = LocalDateTime.now();

    /** 验收时实测温度（℃） */
    @Column(precision = 5, scale = 2)
    private BigDecimal measuredTemp;

    /** 温度是否合格 */
    private Boolean temperatureOk;

    /** 外观是否合格 */
    private Boolean appearanceOk;

    /** 抽检结果描述 */
    @Column(length = 512)
    private String samplingResult;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AcceptanceOverallResult overallResult;

    @Column(length = 512)
    private String notes;

    /** 验收照片 URL（逗号分隔），作为异常/考核证据 */
    @Column(length = 2048)
    private String photoUrls;
}
