package com.cabinet.domain;

import com.cabinet.domain.enums.LossReason;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 报损记录：按批次与柜机登记损耗，供应商/运营可按批次查看损耗原因。
 * 来源：下架报废、盘点盘亏、卡货损坏、退款未收回、温控异常。
 */
@Entity
@Table(name = "loss_record",
        indexes = @Index(columnList = "batch_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LossRecord extends BaseEntity {

    private String lossNo;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cabinet cabinet;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    private ProductBatch batch;

    @Enumerated(EnumType.STRING)
    private LossReason reason;

    private Integer qty;

    /** 成本金额（结算时从应付中扣除或计入运营损耗） */
    @Column(precision = 12, scale = 2)
    private BigDecimal costAmount;

    /** 责任归属：SUPPLIER / OPERATOR / UNKNOWN（结算口径） */
    private String liability;

    private String bizType;
    private Long bizId;

    private String remark;

    /** 差异改判冲销：被冲销的报损不再计入供应商结算/责任口径 */
    @Builder.Default
    private boolean reversed = false;

    /** 冲销该报损的差异单号 */
    private String reversedByDiscrepancyNo;
}
