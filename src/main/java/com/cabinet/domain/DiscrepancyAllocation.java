package com.cabinet.domain;

import com.cabinet.domain.enums.AllocationStatus;
import com.cabinet.domain.enums.DiscrepancyCause;
import com.cabinet.domain.enums.LiabilityParty;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 差异分摊行：把总差异按"发生时间 + 证据"拆成的逐笔数量。
 * 例：系统 10 瓶、实盘 6 瓶，共差 4 瓶，可拆为
 *  13:05 识别漏单 1 瓶（PLATFORM）、15:20 补货少放 2 瓶（REPLENISHER）、
 *  温控异常 1 瓶（FOOD_SAFETY 先行处置），无法确认的部分以 UNCONFIRMED 挂起。
 * 每行确认后触发对应责任分流并记账；改判时旧行置 VOIDED 并新增行，全程留痕。
 */
@Entity
@Table(name = "discrepancy_allocation",
        indexes = {
                @Index(columnList = "discrepancy_id"),
                @Index(columnList = "batch_id"),
                @Index(columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscrepancyAllocation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discrepancy_id")
    private DiscrepancyOrder discrepancy;

    /** 差异原因分类 */
    @Enumerated(EnumType.STRING)
    private DiscrepancyCause cause;

    /** 责任归属方 */
    @Enumerated(EnumType.STRING)
    private LiabilityParty liability;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AllocationStatus status = AllocationStatus.PENDING;

    /** 本行拆分数量（盘亏为正、盘盈为负） */
    private Integer qty;

    /** 该笔差异发生时间（按时间排序、拆分定责的依据） */
    private LocalDateTime occurredAt;

    /** 证据引用：订单号 / 开门会话号 / 补货单号 / 报损号 / 下架单号 */
    private String refOrderNo;
    private String refSessionNo;
    private String refRestockNo;
    private String refLossNo;
    private String refRemovalNo;
    /** 温控异常时间窗说明 */
    private String tempWindow;

    /** 该行成本金额（批次成本单价快照 × 数量） */
    @Column(precision = 12, scale = 2)
    private BigDecimal costAmount;

    /** 确认后产生的报损记录（盘亏类） */
    @ManyToOne(fetch = FetchType.LAZY)
    private LossRecord lossRecord;

    /** 确认/挂起/改判说明 */
    @Column(length = 1000)
    private String remark;

    /** 改判时指向被其冲销的原分摊行（null 表示首次判定） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversed_allocation_id")
    private DiscrepancyAllocation reversedAllocation;

    private LocalDateTime confirmedAt;
    /** 确认/改判操作人 */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser confirmedBy;
}
