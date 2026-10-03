package com.campus.food.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 供应商整改材料（针对考核事件提交） */
@Entity
@Table(name = "rectification_materials")
@Getter
@Setter
@NoArgsConstructor
public class RectificationMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "assessment_id")
    private AssessmentEvent assessment;

    /** 提交人（供应商账号） */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "submitted_by")
    private AppUser submittedBy;

    @Column(nullable = false)
    private LocalDateTime submittedAt = LocalDateTime.now();

    @Column(nullable = false, length = 128)
    private String title;

    /** 整改措施说明（原因分析、整改动作、预防机制） */
    @Column(nullable = false, length = 2048)
    private String content;

    /** 佐证附件（图片/文档标识，逗号分隔） */
    @Column(length = 1024)
    private String attachments;

    /** 采购负责人确认进入重点验收时的备注 */
    @Column(length = 512)
    private String reviewNote;

    private LocalDateTime reviewedAt;
}
