package com.cabinet.domain;

import com.cabinet.domain.enums.FollowUpType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 差异单后续接回记录：补传照片、重复盘点、跨班次补货、已结算订单等
 * 事后出现的证据/业务一律接回原差异单，确保改判时能看清每一次数量与责任变化。
 */
@Entity
@Table(name = "discrepancy_followup",
        indexes = @Index(columnList = "discrepancy_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscrepancyFollowUp extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discrepancy_id")
    private DiscrepancyOrder discrepancy;

    @Enumerated(EnumType.STRING)
    private FollowUpType type;

    /** 接回的关联单据号（补货单/盘点单/结算单/订单等，按类型解释） */
    private String refNo;

    /** 补传照片 URL（可多张逗号分隔） */
    @Column(length = 2000)
    private String photoUrls;

    /** 该次接回带来的数量变化（如重复盘点点数、跨班次补货量），无数量为 0 */
    @Builder.Default
    private Integer qtyChange = 0;

    /** 提交人 */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser submittedBy;

    private LocalDateTime happenedAt;

    @Column(length = 1000)
    private String remark;
}
