package com.cabinet.domain;

import com.cabinet.domain.enums.CauseStatus;
import com.cabinet.domain.enums.DiscrepancyCause;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 差异拆账条目：把盘亏数量按“发生时间 + 证据”拆到不同成因与责任，
 * 不把全部差额简单记到补货员名下。
 *
 * 每条可挂关联业务证据（补货单/开门会话/订单/报损/温控时段）；
 * 责任确认后按成因进入对应下游流程（见 followUps）。
 * 改判时旧条目置 REVERSED，另建条目，全程留痕。
 */
@Entity
@Table(name = "discrepancy_cause_item",
        indexes = {
                @Index(columnList = "discrepancy_id"),
                @Index(columnList = "cause")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscrepancyCauseItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    private DiscrepancyOrder discrepancy;

    @Enumerated(EnumType.STRING)
    private DiscrepancyCause cause;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CauseStatus status = CauseStatus.CONFIRMED;

    /** 该成因分摊的数量（正数） */
    private Integer qty;

    /** 事件发生时间（用于按时间排序拆账） */
    private LocalDateTime occurredAt;

    /** 责任归属：REPLENISHER / SUPPLIER / PLATFORM(识别/销售) / CUSTOMER / UNKNOWN */
    private String liability;

    /** 成本金额快照（结算/评分用） */
    @Column(precision = 12, scale = 2)
    private BigDecimal costAmount;

    /** 关联业务类型与主键（RESTOCK_ORDER/DOOR_SESSION/ORDER_RECORD/LOSS_RECORD/TEMP 等） */
    private String refType;
    private String refNo;

    /** 该条目确认时产生的报损记录（改判冲销用） */
    private Long lossId;

    @Column(length = 1000)
    private String evidenceNote;

    /** 第几次判定（首次 1，改判递增） */
    @Builder.Default
    private Integer judgeRound = 1;

    /** 若由改判冲销，指向取而代之的新条目 */
    @ManyToOne(fetch = FetchType.LAZY)
    private DiscrepancyCauseItem reversedBy;
}
