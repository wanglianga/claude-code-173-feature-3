package com.cabinet.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * 差异单操作/接回流水：补传照片、重复盘点、跨班次补货、已结算订单接回、
 * 改判都追加一条流水，记录每次数量与责任变化，确保之后改判仍可回看全过程。
 */
@Entity
@Table(name = "discrepancy_trace",
        indexes = @Index(columnList = "discrepancy_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscrepancyTrace extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    private DiscrepancyOrder discrepancy;

    /** 动作：CREATE/ALLOCATE/PENDING/PHOTO_UPLOAD/RECOUNT/CROSS_SHIFT_RESTOCK/
     *  SETTLED_ORDER_LINK/FOOD_SAFETY/FOLLOW_UP/RE_JUDGE/FREEZE/UNFREEZE */
    private String action;

    /** 该动作引起的盘亏数量变化（正=差额增大，负=差额减小/冲销） */
    private Integer qtyChange;

    /** 接回/关联的业务类型与单号 */
    private String refType;
    private String refNo;

    @Column(length = 1000)
    private String detail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id")
    private AppUser operator;
}
