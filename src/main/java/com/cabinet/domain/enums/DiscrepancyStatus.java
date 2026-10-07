package com.cabinet.domain.enums;

/**
 * 差异核查单状态：
 * OPEN       已建立、证据已汇聚，等待运营按时间/证据拆分定责；
 * PARTIALLY_CONFIRMED 部分数量已确认责任，仍有挂起复核行（相关批次仍冻结）；
 * CONFIRMED  全部差异数量已确认责任且无挂起行；
 * RESOLVED   已确认并完成全部责任分流（补货员/供应商/算法/食安），冻结解除；
 * REOPENED   结单后因补证/重复盘点/跨班次补货/已结算订单而重新打开核查。
 */
public enum DiscrepancyStatus {
    OPEN,
    PARTIALLY_CONFIRMED,
    CONFIRMED,
    RESOLVED,
    REOPENED
}
