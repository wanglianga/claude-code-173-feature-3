package com.cabinet.domain.enums;

/** 报损原因 */
public enum LossReason {
    SCRAP_EXPIRED,      // 过期报废
    SCRAP_NEAR_EXPIRY,  // 临期报废
    SCRAP_DAMAGED,      // 破损报废
    TEMP_ABUSE,         // 温控异常报损
    SHRINKAGE,          // 盘点盘亏
    JAM_DAMAGED,        // 卡货损坏
    REFUND_NO_RETURN,   // 退款但商品未收回
    LOSS_MISSED,        // 报损漏记（差异调查确认的实际损耗）
    RECOGNITION_MISS    // 识别漏单（用户拿走未出单）
}
