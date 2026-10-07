package com.cabinet.domain;

import com.cabinet.domain.enums.FollowUpStatus;
import com.cabinet.domain.enums.FollowUpType;
import jakarta.persistence.*;
import lombok.*;

/**
 * 差异责任确认后的下游流转记录：
 * 补货员结算与评分、供应商账单、识别算法样本、食品安全复核各走各的流程。
 * 温控异常（食品安全）在拆账确认 TEMP_ABUSE 的同时即先行处置，不等普通追责结束。
 */
@Entity
@Table(name = "discrepancy_follow_up",
        indexes = {
                @Index(columnList = "discrepancy_id"),
                @Index(columnList = "type,status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscrepancyFollowUp extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    private DiscrepancyOrder discrepancy;

    @ManyToOne(fetch = FetchType.LAZY)
    private DiscrepancyCauseItem causeItem;

    @Enumerated(EnumType.STRING)
    private FollowUpType type;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private FollowUpStatus status = FollowUpStatus.PENDING;

    /** 数量/金额/扣分明细等说明 */
    @Column(length = 1000)
    private String detail;

    /** 外部/下游单据号（结算单、账单、样本批次、安全处置单号） */
    private String refNo;
}
