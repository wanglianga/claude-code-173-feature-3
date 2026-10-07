package com.cabinet.domain.enums;

/**
 * 盘点差异原因分类（核查单按时间与证据拆分定责的五类原因 + 盘盈 + 待查）。
 * 每一项对应一条差异分摊行，按发生时间排序，逐瓶/逐件归责，不允许整额挂到单一责任方。
 */
public enum DiscrepancyCause {
    SALE_NOT_DEDUCTED,   // 销售没有扣减（识别漏扣/系统故障）
    RESTOCK_SHORTAGE,    // 补货时少放（补货员责任）
    LOSS_MISSED,         // 报损漏记（损耗已发生但未登记）
    RECOGNITION_MISSED,  // 识别漏单（商品被拿走未识别/未生成订单）
    TEMP_DAMAGE,         // 冷藏温度异常造成损耗
    OVERCOUNT,           // 盘盈（实盘多于系统，反向调整）
    UNCONFIRMED          // 无法确认，先挂起复核
}
