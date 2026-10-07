package com.cabinet.domain.enums;

/**
 * 差异分摊行（按时间/证据拆开的每一笔数量）的处理状态。
 * PENDING   待核查；
 * SUSPENDED 无法确认，挂起复核（批次结算/对账继续冻结）；
 * CONFIRMED 已确认责任并完成记账/分流；
 * VOIDED    改判后原分摊作废（保留留痕，数量转移到新分摊行）。
 */
public enum AllocationStatus {
    PENDING,
    SUSPENDED,
    CONFIRMED,
    VOIDED
}
