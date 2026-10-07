package com.cabinet.domain.enums;

/**
 * 补货员差异责任的结算/评分处置状态。
 * PENDING   待进入补货员结算；
 * DEDUCTED  已在补货员结算中扣款；
 * SCORED    已计入评分（仅扣分不涉及金额，如挂起后判非补货员责任）；
 * REVERSED  改判后撤销（退款/回补评分）。
 */
public enum PenaltyStatus {
    PENDING,
    DEDUCTED,
    SCORED,
    REVERSED
}
