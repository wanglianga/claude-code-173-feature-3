package com.cabinet.domain.enums;

/**
 * 差异拆账条目的处理/流转状态：
 * PENDING   挂起复核（责任或数量暂不能确认）
 * CONFIRMED 已确认责任
 * REVERSED  已被后续改判冲销（数量与责任转移到新条目，保留留痕）
 */
public enum CauseStatus { PENDING, CONFIRMED, REVERSED }
