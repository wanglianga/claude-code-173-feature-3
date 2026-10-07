package com.cabinet.dto;

import com.cabinet.domain.*;

import java.util.List;

/**
 * 差异核查证据视图：一张差异单在同一视图中汇聚核查时间窗内的全部证据——
 * 补货前后库存（批次台账）、补货照片（补货单/明细）、销售订单与开门记录、
 * 报损记录、温控曲线，供运营按发生时间把差异逐瓶拆开定责。
 */
public record DiscrepancyEvidenceView(
        DiscrepancyOrder discrepancy,
        /** 时间窗内本批次台账流水（可看补货前后库存变化） */
        List<BatchLedger> batchLedgers,
        /** 相关补货单（含整单照片） */
        List<RestockOrder> restockOrders,
        /** 相关补货明细（含货道、数量、上架照片、温度快照） */
        List<RestockItem> restockItems,
        /** 时间窗内柜机开门记录 */
        List<DoorSession> doorSessions,
        /** 时间窗内本商品销售订单（含状态，识别漏单表现为有开门无订单） */
        List<OrderRecord> orders,
        /** 开门会话下的识别事件（含视频片段） */
        List<RecognitionEvent> recognitionEvents,
        /** 时间窗内报损记录 */
        List<LossRecord> lossRecords,
        /** 时间窗内下架单 */
        List<RemovalOrder> removalOrders,
        /** 温控曲线（越限点带 alarm 标记） */
        List<TempLog> tempLogs,
        /** 系统按时间线给出的候选原因提示（供人工参考，不自动定责） */
        List<CandidateCause> candidateCauses) {

    /** 候选原因：系统从证据中归纳的可能原因 + 发生时间 + 引用证据 + 建议数量 */
    public record CandidateCause(
            String cause,
            String occurredAt,
            String evidence,
            Integer suggestedQty,
            String detail) {}
}
