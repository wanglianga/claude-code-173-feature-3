package com.cabinet.domain;

import com.cabinet.domain.enums.DiscrepancyStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 盘点差异核查单：围绕"系统库存 vs 实盘"的差异，汇聚补货前后库存、补货照片、
 * 销售订单、开门记录、报损记录与温控曲线，按发生时间与证据把差异数量拆分成多条
 * 分摊行（{@link DiscrepancyAllocation}）逐笔定责；无法确认的行挂起复核。
 *
 * 差异查清前仅冻结相关批次的补货结算与供应商对账，其他商品/批次照常结算。
 * 补传照片、重复盘点、跨班次补货、已结算订单通过 {@link DiscrepancyFollowUp} 接回本原单，
 * 改判历史全程留痕，保证每一次数量与责任变化可追溯。
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

    /** 差异核查单号 DC... */
    private String discrepancyNo;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cabinet cabinet;

    /** 差异所在货道 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    /** 差异核查针对的批次（冻结该批次结算/对账的最小单元） */
    @ManyToOne(fetch = FetchType.LAZY)
    private ProductBatch batch;

    /** 来源盘点单（可为空：支持直接按柜机/批次立案核查） */
    @ManyToOne(fetch = FetchType.LAZY)
    private StocktakeOrder stocktake;

    /** 建单人 */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser createdBy;

    /** 最近核查/改判人 */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser handledBy;

    /** 核查时间窗起点（默认取本批次最近一次补货时间） */
    private LocalDateTime windowStart;
    /** 核查时间窗终点（盘点时间） */
    private LocalDateTime windowEnd;

    /** 系统显示数量（快照） */
    private Integer systemQty;
    /** 实盘数量（快照） */
    private Integer countedQty;
    /** 差异数量 = systemQty - countedQty（盘亏为正，盘盈为负） */
    private Integer diffQty;

    /** 建单时该批次在柜机的在库余量（补货后/盘点前快照依据） */
    private Integer stockBeforeOnHand;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DiscrepancyStatus status = DiscrepancyStatus.OPEN;

    /** 相关批次补货结算/供应商对账是否处于冻结中 */
    @Builder.Default
    private boolean settlementFrozen = true;

    /** 冻结时间 */
    private LocalDateTime frozenAt;
    /** 解冻时间 */
    private LocalDateTime unfrozenAt;

    /** 已确认定责数量（盘亏为正） */
    @Builder.Default
    private Integer confirmedQty = 0;
    /** 挂起复核数量 */
    @Builder.Default
    private Integer suspendedQty = 0;
    /** 已作废（改判冲销）数量 */
    @Builder.Default
    private Integer voidedQty = 0;

    /** 盘点/核查备注 */
    @Column(length = 1000)
    private String remark;

    private LocalDateTime resolvedAt;
}
