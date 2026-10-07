package com.cabinet.domain.enums;

/**
 * 差异责任归属方。确认责任后分别走对应流程：
 * REPLENISHER 补货员：结算扣罚与评分；
 * SUPPLIER     供应商：账单/对账调整；
 * PLATFORM     平台/运营：识别算法样本复核（漏识别、销售未扣减的系统侧原因）；
 * FOOD_SAFETY  食品安全：温控异常商品先行安全处置（不等普通追责）；
 * NONE         无法归责/盘盈等无罚则场景。
 */
public enum LiabilityParty {
    REPLENISHER,
    SUPPLIER,
    PLATFORM,
    FOOD_SAFETY,
    NONE
}
