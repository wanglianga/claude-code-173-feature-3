package com.cabinet.dto;

import com.cabinet.domain.*;

import java.util.List;

/**
 * 盘点差异调查证据视图：在一个响应内给调查员全部研判材料——
 * 补货前后库存（批次台账）、补货明细与补货照片、时间窗内销售订单与明细、
 * 开门记录与多源识别事件（漏单研判）、报损记录、温控曲线，
 * 以及差异单自身的拆账条目、下游流转、接回流水与证据挂接。
 */
public record DiscrepancyEvidenceView(
        DiscrepancyOrder discrepancy,
        List<DiscrepancyCauseItem> causes,
        List<DiscrepancyFollowUp> followUps,
        List<DiscrepancyTrace> traces,
        List<DiscrepancyEvidence> evidenceLinks,
        List<BatchLedger> batchLedgers,
        List<RestockItem> restockItems,
        List<OrderRecord> saleOrders,
        List<OrderItem> orderItems,
        List<DoorSession> doorSessions,
        List<RecognitionEvent> recognitionEvents,
        List<LossRecord> lossRecords,
        List<TempLog> tempLogs) {
}
