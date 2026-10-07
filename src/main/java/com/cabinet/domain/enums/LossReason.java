package com.cabinet.domain.enums;

/** 报损原因 */
public enum LossReason {
    SCRAP_EXPIRED,      // 过期报废
    SCRAP_NEAR_EXPIRY,  // 临期报废
    SCRAP_DAMAGED,      // 破损报废
    TEMP_ABUSE,         // 温控异常报损
    SHRINKAGE,          // 盘点盘亏 / 报损漏记 / 放错货道纠正
    JAM_DAMAGED,        // 卡货损坏
    REFUND_NO_RETURN,   // 退款但商品未收回
    SALE_NOT_DEDUCTED,  // 差异核查：销售未扣减
    RECOGNITION_MISSED, // 差异核查：识别漏单
    RESTOCK_SHORTAGE    // 差异核查：补货少放/到货短少
}
