package com.cabinet.domain;

import com.cabinet.domain.enums.Disposition;
import com.cabinet.domain.enums.FoodSafetyStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 食品安全复核：差异核查中发现冷藏温度异常时，受影响商品先进入安全处置，
 * 不等普通追责结束。记录隔离/报废/解除/召回的处置结论与数量、责任人与时间。
 */
@Entity
@Table(name = "food_safety_review",
        indexes = @Index(columnList = "discrepancy_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FoodSafetyReview extends BaseEntity {

    private String reviewNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discrepancy_id")
    private DiscrepancyOrder discrepancy;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cabinet cabinet;

    @ManyToOne(fetch = FetchType.LAZY)
    private ProductBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private FoodSafetyStatus status = FoodSafetyStatus.PENDING;

    /** 受影响/处置数量 */
    private Integer qty;

    /** 温度异常起止时间窗 */
    private LocalDateTime tempAbuseFrom;
    private LocalDateTime tempAbuseTo;
    private Double peakTempC;

    /** 处置去向（报废/退供等） */
    @Enumerated(EnumType.STRING)
    private Disposition disposition;

    /** 处置后产生的报损（报废时） */
    @ManyToOne(fetch = FetchType.LAZY)
    private LossRecord lossRecord;

    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser handledBy;

    private LocalDateTime handledAt;

    @Column(length = 1000)
    private String remark;
}
