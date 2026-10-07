package com.cabinet.domain;

import com.cabinet.domain.enums.DiscrepancyStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 盘点差异调查单：围绕某柜机某批次“系统库存 - 实盘”的差额查清楚成因。
 * 一张差异单针对一个盘点差异行（柜机 + 货道 + 批次），
 * 差额按发生时间与证据拆到多条成因（DiscrepancyCauseItem），
 * 无法确认的数量挂起复核；查清前仅冻结相关批次的补货结算与供应商对账。
 *
 * 补传照片、重复盘点、跨班次补货、已结算订单都接回本单（DiscrepancyTrace），
 * 改判后仍能看清每一次数量与责任变化。
 */
@Entity
@Table(name = "discrepancy_order",
        indexes = {
                @Index(columnList = "cabinet_id"),
                @Index(columnList = "batch_id"),
                @Index(columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscrepancyOrder extends BaseEntity {

    /** 差异单号 DC... */
    private String discrepancyNo;

    @ManyToOne(fetch = FetchType.LAZY)
    private StocktakeOrder stocktake;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cabinet cabinet;

    @ManyToOne(fetch = FetchType.LAZY)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    private ProductBatch batch;

    /** 盘点时系统库存快照 */
    private Integer systemQty;
    /** 实盘数量 */
    private Integer countedQty;
    /** 原始盘亏数量（>0 为盘亏） */
    private Integer shortageQty;

    /** 已拆账并确认责任的数量 */
    @Builder.Default
    private Integer allocatedQty = 0;
    /** 挂起复核、无法确认的数量 */
    @Builder.Default
    private Integer pendingQty = 0;
    /** 累计已做库存/报损影响的数量（防止重复扣减） */
    @Builder.Default
    private Integer stockEffectedQty = 0;

    /** 调查时间窗起点/终点（证据装配范围，默认取补货前后到盘点时刻） */
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DiscrepancyStatus status = DiscrepancyStatus.INVESTIGATING;

    /** 责任人（运营调查员） */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser investigator;

    /** 最近一次结论/改判说明 */
    @Column(length = 2000)
    private String conclusion;

    private LocalDateTime resolvedAt;
}
