package com.cabinet.domain.enums;

/**
 * 差异成因（按发生时间与证据把盘亏数量拆开归责）：
 * SALE_NOT_DEDUCTED 销售没有扣减（识别漏扣/已结算订单漏扣库存）
 * RESTOCK_SHORT     补货时少放
 * LOSS_MISSED       报损漏记（实际损耗/下架未登记）
 * RECOGNITION_MISS  识别漏单（用户拿走未识别出单）
 * TEMP_ABUSE        冷藏温度异常造成的损耗（食品安全，先处置）
 * PENDING           无法确认，挂起复核
 */
public enum DiscrepancyCause {
    SALE_NOT_DEDUCTED,
    RESTOCK_SHORT,
    LOSS_MISSED,
    RECOGNITION_MISS,
    TEMP_ABUSE,
    PENDING
}
