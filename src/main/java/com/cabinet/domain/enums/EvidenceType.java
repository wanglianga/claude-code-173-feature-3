package com.cabinet.domain.enums;

/**
 * 差异证据类型：盘点单要能关联补货前后的库存、补货照片、销售订单、
 * 开门记录、报损记录和温控曲线。
 */
public enum EvidenceType {
    STOCK_BEFORE_RESTOCK,  // 补货前库存
    STOCK_AFTER_RESTOCK,   // 补货后库存
    RESTOCK_PHOTO,         // 补货照片
    SALE_ORDER,            // 销售订单
    DOOR_SESSION,          // 开门记录
    LOSS_RECORD,           // 报损记录
    TEMP_LOG,              // 温控曲线
    RECOGNITION_EVENT,     // 识别事件（漏单研判）
    RESTOCK_ORDER,         // 补货单（跨班次补货）
    STOCKTAKE_ORDER,       // 重复盘点
    UPLOADED_PHOTO,        // 事后补传照片
    SETTLED_ORDER,         // 已结算订单
    LEDGER                 // 批次台账流水
}
