package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 识别算法样本：差异确认为识别漏单/销售未扣减等平台侧原因后，
 * 把相关开门会话与识别事件沉淀为算法训练/复核样本，回流模型优化。
 */
@Entity
@Table(name = "recognition_sample",
        indexes = @Index(columnList = "discrepancy_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecognitionSample extends BaseEntity {

    private String sampleNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discrepancy_id")
    private DiscrepancyOrder discrepancy;

    @ManyToOne(fetch = FetchType.LAZY)
    private DoorSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    private ProductBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    private DiscrepancyAllocation allocation;

    /** 漏识别/漏扣数量 */
    private Integer qty;

    /** 样本标签：RECOGNITION_MISSED / SALE_NOT_DEDUCTED */
    private String label;

    /** 视频片段/识别原文等样本位置 */
    @Column(length = 1000)
    private String evidenceRef;

    /** 复核结论：待算法团队处理 / 已采纳回流 */
    @Builder.Default
    private boolean reviewed = false;
    private String reviewNote;
    private LocalDateTime reviewedAt;
}
