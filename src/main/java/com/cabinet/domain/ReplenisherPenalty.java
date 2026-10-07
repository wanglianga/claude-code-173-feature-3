package com.cabinet.domain;

import com.cabinet.domain.enums.PenaltyStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补货员结算与评分：差异责任确认到补货员（补货少放等）后生成，
 * 进入补货员结算扣款并计入评分；改判无责时冲销（REVERSED）并回补评分。
 */
@Entity
@Table(name = "replenisher_penalty",
        indexes = @Index(columnList = "discrepancy_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReplenisherPenalty extends BaseEntity {

    private String penaltyNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discrepancy_id")
    private DiscrepancyOrder discrepancy;

    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser replenisher;

    @ManyToOne(fetch = FetchType.LAZY)
    private ProductBatch batch;

    /** 关联分摊行 */
    @ManyToOne(fetch = FetchType.LAZY)
    private DiscrepancyAllocation allocation;

    private Integer qty;

    /** 扣款金额（按成本或约定罚率） */
    @Column(precision = 12, scale = 2)
    private BigDecimal deductAmount;

    /** 扣分（评分） */
    private Integer scorePoints;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PenaltyStatus status = PenaltyStatus.PENDING;

    /** 结算单号（进入补货员结算后回填） */
    private String settledInNo;
    private LocalDateTime settledAt;

    @Column(length = 1000)
    private String remark;
}
