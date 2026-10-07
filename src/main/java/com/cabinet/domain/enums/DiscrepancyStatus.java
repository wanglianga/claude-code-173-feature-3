package com.cabinet.domain.enums;

/**
 * 差异单状态：
 * INVESTIGATING 调查中（相关批次补货结算/供应商对账冻结）
 * PENDING_REVIEW 数量已拆分但存在无法确认部分，挂起复核
 * RESOLVED 全部数量责任已确认（可能仍有挂起数量 → 用 PENDING_REVIEW）
 * RE_JUDGED 改判（由后续补传照片/重复盘点/跨班次补货/已结算订单接回后重新判定）
 */
public enum DiscrepancyStatus { INVESTIGATING, PENDING_REVIEW, RESOLVED, RE_JUDGED }
