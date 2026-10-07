package com.cabinet.domain.enums;

/**
 * 差异单后续接回类型。补传照片、重复盘点、跨班次补货、已结算订单等
 * 事后证据/业务都必须接回原差异单，保证改判时能看清每一次数量与责任变化。
 */
public enum FollowUpType {
    PHOTO_SUPPLEMENT,  // 补传补货/上架照片
    RESTOCK_CROSS_SHIFT, // 跨班次补货记录接回
    RECOUNT,           // 重复盘点
    SETTLED_ORDER,     // 已结算订单（事后发现与差异相关）
    MANUAL_EVIDENCE,   // 人工补充其他证据
    REOPEN             // 重新打开核查
}
