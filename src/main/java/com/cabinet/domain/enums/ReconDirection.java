package com.cabinet.domain.enums;

/**
 * 供应商对账调整方向（差异确认责任在供应商/补货等场景下生成，
 * 供下一期供应商账单核销；若对应批次已结算，则挂到后续账期）。
 * DEDUCT  从应付中扣减（供应商承担）；
 * ADD     补付供应商（如改判后供应商无责，需回补）。
 */
public enum ReconDirection {
    DEDUCT,
    ADD
}
