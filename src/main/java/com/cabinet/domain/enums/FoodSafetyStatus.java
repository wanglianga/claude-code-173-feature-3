package com.cabinet.domain.enums;

/**
 * 食品安全复核处置状态（温度异常商品优先于普通追责流转）。
 * PENDING   差异单发现温控异常，已隔离待处置；
 * DISPOSED  已报废销毁（记 TEMP_ABUSE 报损，按温控责任归责）；
 * RETURNED  评估合格/解除风险，退回可售（解除隔离）；
 * RECALLED  召回供应商处理（退供，不产生运营报损）。
 */
public enum FoodSafetyStatus {
    PENDING,
    DISPOSED,
    RETURNED,
    RECALLED
}
