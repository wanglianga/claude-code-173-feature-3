package com.cabinet.domain;

import com.cabinet.domain.enums.ReconDirection;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供应商账单对账调整：差异责任确认到供应商（如到货短少）或改判回补时生成，
 * 在下一期供应商结算单中核销；批次结算在差异查清前被冻结，调整不影响其他商品结算。
 */
@Entity
@Table(name = "supplier_recon_adjustment",
        indexes = @Index(columnList = "discrepancy_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconAdjustment extends BaseEntity {

    private String adjustmentNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discrepancy_id")
    private DiscrepancyOrder discrepancy;

    @ManyToOne(fetch = FetchType.LAZY)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    private ProductBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    private DiscrepancyAllocation allocation;

    @Enumerated(EnumType.STRING)
    private ReconDirection direction;

    private Integer qty;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    /** 是否已被某结算单核销 */
    @Builder.Default
    private boolean applied = false;
    private String appliedInSettlementNo;
    private LocalDateTime appliedAt;

    /** 改判后作废（未核销直接作废；已核销则另出反向调整） */
    @Builder.Default
    @Column(nullable = false)
    private boolean voided = false;

    @Column(length = 1000)
    private String remark;
}
