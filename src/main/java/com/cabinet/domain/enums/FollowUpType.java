package com.cabinet.domain.enums;

/**
 * 确认责任后分别流转的下游流程：
 * REPLENISHER_SETTLEMENT 补货员结算与评分
 * SUPPLIER_BILL           供应商账单
 * ALGORITHM_SAMPLE        识别算法样本（漏单/误识别回流训练）
 * FOOD_SAFETY_REVIEW      食品安全复核（温控异常商品先安全处置，不等普通追责）
 * NONE                    无需下游（如销售漏扣仅补扣/盘盈）
 */
public enum FollowUpType {
    REPLENISHER_SETTLEMENT,
    SUPPLIER_BILL,
    ALGORITHM_SAMPLE,
    FOOD_SAFETY_REVIEW,
    NONE
}
