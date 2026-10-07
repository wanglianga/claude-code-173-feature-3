package com.cabinet.domain;

import com.cabinet.domain.enums.EvidenceType;
import jakarta.persistence.*;
import lombok.*;

/**
 * 差异证据关联：盘点单关联补货前后库存、补货照片、销售订单、开门记录、
 * 报损记录、温控曲线，以及事后补传照片/重复盘点/跨班次补货/已结算订单。
 * 证据为只读快照式挂接（bizType + bizNo/bizId + 说明/URL）。
 */
@Entity
@Table(name = "discrepancy_evidence",
        indexes = @Index(columnList = "discrepancy_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscrepancyEvidence extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    private DiscrepancyOrder discrepancy;

    @Enumerated(EnumType.STRING)
    private EvidenceType type;

    /** 业务类型（RESTOCK_ORDER/RESTOCK_ITEM/ORDER_RECORD/DOOR_SESSION/LOSS_RECORD/TEMP_LOG/STOCKTAKE_ORDER 等） */
    private String bizType;
    private Long bizId;
    private String bizNo;

    /** 证据 URL（补货照片/补传照片/视频片段） */
    private String url;

    @Column(length = 1000)
    private String note;
}
