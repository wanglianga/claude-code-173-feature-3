package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.dto.DiscrepancyEvidenceView;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 盘点差异核查服务（本特性核心）。
 *
 * 围绕"系统显示 vs 实盘"的差异立案：
 * 1. 建单即冻结相关批次的补货结算与供应商对账（其他商品/批次照常结算）；
 * 2. 证据视图一次汇聚补货前后库存（批次台账）、补货照片、销售订单、开门记录、
 *    识别事件、报损/下架记录与温控曲线，并给出按时间线排列的候选原因；
 * 3. 运营按"发生时间 + 证据"把差异逐瓶拆成分摊行定责；同一时段多种可能时，
 *    不允许把全部差额记到一个责任方，无法确认的行挂起复核，批次继续冻结；
 * 4. 温度异常商品通过食品安全复核先行处置（隔离/报废/解除），不等普通追责；
 * 5. 责任确认后分别分流：补货员结算扣罚与评分、供应商账单对账调整、
 *    识别算法样本回流、食品安全处置留痕；
 * 6. 补传照片、重复盘点、跨班次补货、已结算订单通过接回记录挂回原差异单；
 *    改判作废原分摊行（冲销库存/台账/罚则）并新增行，全程留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscrepancyService {

    /** 补货员每件盘亏责任扣分 */
    private static final int SCORE_POINTS_PER_QTY = 5;

    private final DiscrepancyOrderRepository discrepancyRepository;
    private final DiscrepancyAllocationRepository allocationRepository;
    private final DiscrepancyFollowUpRepository followUpRepository;
    private final FoodSafetyReviewRepository foodSafetyRepository;
    private final ReplenisherPenaltyRepository penaltyRepository;
    private final RecognitionSampleRepository sampleRepository;
    private final ReconAdjustmentRepository reconRepository;

    private final StocktakeOrderRepository stocktakeRepository;
    private final StocktakeItemRepository stocktakeItemRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final ProductRepository productRepository;
    private final ProductBatchRepository batchRepository;
    private final AppUserRepository userRepository;
    private final ChannelStockRepository stockRepository;
    private final BatchLedgerRepository ledgerRepository;
    private final RestockOrderRepository restockOrderRepository;
    private final RestockItemRepository restockItemRepository;
    private final DoorSessionRepository sessionRepository;
    private final OrderRecordRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final RecognitionEventRepository recognitionEventRepository;
    private final LossRecordRepository lossRepository;
    private final RemovalOrderRepository removalRepository;
    private final RemovalItemRepository removalItemRepository;
    private final TempLogRepository tempLogRepository;
    private final AlertRepository alertRepository;

    private final BatchLedgerService ledgerService;
    private final AlertService alertService;
    private final BizEventPublisher bizEventPublisher;

    // ============================ 建单 / 冻结 ============================

    /** 从盘点单行立案：channelCode + batchNo 定位有差异的盘点行 */
    @Transactional
    public DiscrepancyOrder openFromStocktake(String stocktakeNo, String channelCode, String batchNo,
                                              String username) {
        StocktakeOrder st = stocktakeRepository.findByOrderNo(stocktakeNo)
                .orElseThrow(() -> ApiException.notFound("盘点单不存在: " + stocktakeNo));
        StocktakeItem item = stocktakeItemRepository.findByStocktakeId(st.getId()).stream()
                .filter(i -> i.getChannel().getCode().equals(channelCode)
                        && i.getBatch().getBatchNo().equals(batchNo))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("盘点行不存在或不匹配"));
        if (item.getCountedQty() == null || item.getDiffQty() == null || item.getDiffQty() == 0) {
            throw ApiException.badRequest("该盘点行无差异，无需立案核查");
        }
        // 差异单内部统一口径：diffQty = systemQty - countedQty（盘亏为正、盘盈为负）
        return buildOrder(st.getCabinet(), item.getChannel(), item.getProduct(), item.getBatch(),
                st, item.getSystemQty(), item.getCountedQty(),
                item.getSystemQty() - item.getCountedQty(), username);
    }

    /** 直接立案核查（盘点单之外发现差异，或盘点单尚未录入时使用） */
    @Transactional
    public DiscrepancyOrder open(String cabinetNo, String channelCode, String batchNo,
                                 Integer systemQty, Integer countedQty, String username, String remark) {
        Cabinet cabinet = cabinetRepository.findByCabinetNo(cabinetNo)
                .orElseThrow(() -> ApiException.notFound("柜机不存在: " + cabinetNo));
        ProductBatch batch = batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> ApiException.badRequest("批次不存在: " + batchNo));
        Product product = batch.getProduct();
        Channel channel = channelCode != null
                ? channelRepository.findByCabinetIdAndCode(cabinet.getId(), channelCode).orElse(null)
                : null;
        if (systemQty == null || countedQty == null) {
            throw ApiException.badRequest("系统数量与实盘数量必填");
        }
        DiscrepancyOrder order = buildOrder(cabinet, channel, product, batch, null,
                systemQty, countedQty, systemQty - countedQty, username);
        if (remark != null) {
            order.setRemark(remark);
            discrepancyRepository.save(order);
        }
        return order;
    }

    private DiscrepancyOrder buildOrder(Cabinet cabinet, Channel channel, Product product,
                                        ProductBatch batch, StocktakeOrder stocktake,
                                        int systemQty, int countedQty, int diffQty, String username) {
        // 同批次在柜未结差异单不重复立案
        List<DiscrepancyOrder> open = discrepancyRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId());
        boolean dup = open.stream().anyMatch(d -> d.getCabinet().getId().equals(cabinet.getId())
                && d.getStatus() != DiscrepancyStatus.RESOLVED);
        if (dup) {
            throw ApiException.conflict("该柜机批次存在未结差异核查单，请在原单上继续核查或改判");
        }

        LocalDateTime windowEnd = stocktake != null && stocktake.getCompletedAt() != null
                ? stocktake.getCompletedAt() : LocalDateTime.now();
        LocalDateTime windowStart = resolveWindowStart(batch, cabinet, windowEnd);
        int onHand = stockRepository.findByBatchId(batch.getId()).stream()
                .filter(s -> s.getChannel().getCabinet().getId().equals(cabinet.getId()))
                .mapToInt(ChannelStock::getQtyOnHand).sum();

        AppUser user = userRepository.findByUsername(username).orElse(null);
        DiscrepancyOrder order = DiscrepancyOrder.builder()
                .discrepancyNo(genNo("DC"))
                .cabinet(cabinet).channel(channel).product(product).batch(batch)
                .stocktake(stocktake).createdBy(user)
                .windowStart(windowStart).windowEnd(windowEnd)
                .systemQty(systemQty).countedQty(countedQty).diffQty(diffQty)
                .stockBeforeOnHand(onHand)
                .status(DiscrepancyStatus.OPEN)
                .settlementFrozen(true).frozenAt(LocalDateTime.now())
                .remark(stocktake != null ? "来源于盘点单 " + stocktake.getOrderNo() : null)
                .build();
        discrepancyRepository.save(order);

        alertService.raise(cabinet, AlertType.STOCK_DISCREPANCY, "WARN",
                String.format("差异核查单 %s 立案：%s 批次 %s 系统 %d 实盘 %d，差 %d 件，已冻结该批次结算",
                        order.getDiscrepancyNo(), product.getSku(), batch.getBatchNo(),
                        systemQty, countedQty, Math.abs(diffQty)),
                "DISCREPANCY", order.getId());
        bizEventPublisher.publish("DISCREPANCY_OPENED", order.getDiscrepancyNo(),
                cabinet.getCabinetNo(), batch.getBatchNo(),
                "盘点差异立案核查，批次结算冻结 x" + Math.abs(diffQty), null);

        // 温度异常商品先行：建单即按温控曲线隔离并建立食安待处置单
        ensureFoodSafetyQuarantine(order);
        return order;
    }

    /** 时间窗起点：本批次在本柜最近一次补货上架时间（若无则放宽到窗口终点前 7 天） */
    private LocalDateTime resolveWindowStart(ProductBatch batch, Cabinet cabinet, LocalDateTime end) {
        LocalDateTime latestRestock = null;
        for (BatchLedger l : ledgerRepository.findByBatchIdOrderByIdAsc(batch.getId())) {
            if (l.getLedgerType() == LedgerType.RESTOCK_IN && l.getCabinet() != null
                    && l.getCabinet().getId().equals(cabinet.getId())
                    && !l.getCreatedAt().isAfter(end)) {
                latestRestock = l.getCreatedAt();
            }
        }
        return latestRestock != null ? latestRestock : end.minusDays(7);
    }

    // ============================ 证据汇聚 ============================

    @Transactional(readOnly = true)
    public DiscrepancyEvidenceView evidence(String discrepancyNo) {
        DiscrepancyOrder d = must(discrepancyNo);
        Long cabinetId = d.getCabinet().getId();
        LocalDateTime from = d.getWindowStart();
        LocalDateTime to = d.getWindowEnd();

        // 批次台账（补货前后库存变化）
        List<BatchLedger> ledgers = ledgerRepository.findByBatchIdOrderByIdAsc(d.getBatch().getId()).stream()
                .filter(l -> l.getCabinet() == null
                        || (l.getCabinet().getId().equals(cabinetId)
                            && !l.getCreatedAt().isBefore(from) && !l.getCreatedAt().isAfter(to)))
                .toList();

        // 相关补货单与明细（含照片、货道、申报数量、温度快照）
        List<RestockItem> restockItems = restockItemRepository.findByBatchId(d.getBatch().getId()).stream()
                .filter(i -> i.getChannel() != null
                        && i.getChannel().getCabinet().getId().equals(cabinetId))
                .sorted(Comparator.comparing(RestockItem::getId))
                .toList();
        Set<Long> restockOrderIds = new LinkedHashSet<>();
        restockItems.forEach(i -> restockOrderIds.add(i.getRestockOrder().getId()));
        List<RestockOrder> restockOrders = restockOrderIds.stream()
                .map(id -> restockOrderRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RestockOrder::getArrivedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // 时间窗内开门记录
        List<DoorSession> sessions = sessionRepository
                .findByCabinetIdOrderByOpenedAtDesc(cabinetId).stream()
                .filter(s -> s.getOpenedAt() == null
                        || (!s.getOpenedAt().isBefore(from) && !s.getOpenedAt().isAfter(to)))
                .sorted(Comparator.comparing(DoorSession::getOpenedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        List<Long> sessionIds = sessions.stream().map(DoorSession::getId).toList();

        // 识别事件（含视频片段）
        List<RecognitionEvent> recognitionEvents = sessionIds.isEmpty() ? List.of()
                : recognitionEventRepository.findBySessionIdInOrderByDetectedAtAsc(sessionIds).stream()
                    .filter(e -> e.getProduct() != null
                            && e.getProduct().getId().equals(d.getProduct().getId()))
                    .toList();

        // 销售订单（本商品，时间窗内）
        List<OrderRecord> orders = orderRepository.findByCabinetId(cabinetId).stream()
                .filter(o -> o.getChargedAt() != null
                        && !o.getChargedAt().isBefore(from) && !o.getChargedAt().isAfter(to))
                .filter(o -> orderItemRepository.findByOrderId(o.getId()).stream()
                        .anyMatch(oi -> oi.getBatch() != null
                                && oi.getBatch().getId().equals(d.getBatch().getId())))
                .sorted(Comparator.comparing(OrderRecord::getChargedAt))
                .toList();

        // 报损与下架
        List<LossRecord> losses = lossRepository.findByBatchIdOrderByIdAsc(d.getBatch().getId()).stream()
                .filter(l -> l.getCabinet() != null && l.getCabinet().getId().equals(cabinetId)
                        && !l.getCreatedAt().isBefore(from.minusDays(1)) && !l.getCreatedAt().isAfter(to))
                .toList();
        List<RemovalItem> removalItems = new ArrayList<>();
        for (RemovalOrder ro : removalRepository.findAll()) {
            if (!ro.getCabinet().getId().equals(cabinetId)) continue;
            if (ro.getScheduledAt() != null
                    && (ro.getScheduledAt().isBefore(from.minusDays(1)) || ro.getScheduledAt().isAfter(to))) {
                continue;
            }
            removalItems.addAll(removalItemRepository.findByRemovalOrderId(ro.getId()));
        }
        List<RemovalOrder> removalOrders = removalItems.stream()
                .filter(ri -> ri.getBatch().getId().equals(d.getBatch().getId()))
                .map(ri -> removalRepository.findById(ri.getRemovalOrder().getId()).orElse(null))
                .filter(Objects::nonNull).distinct()
                .sorted(Comparator.comparing(RemovalOrder::getScheduledAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // 温控曲线
        List<TempLog> tempLogs = tempLogRepository
                .findByCabinetIdAndRecordedAtBetweenOrderByRecordedAtAsc(cabinetId,
                        from.minusHours(1), to.plusHours(1));

        List<DiscrepancyEvidenceView.CandidateCause> candidates =
                suggestCauses(d, sessions, orders, restockItems, losses, tempLogs);

        return new DiscrepancyEvidenceView(d, ledgers, restockOrders, restockItems, sessions,
                orders, recognitionEvents, losses, removalOrders, tempLogs, candidates);
    }

    /**
     * 按证据时间线归纳候选原因（仅建议，数量默认 1，最终由人工按证据拆分确认）。
     */
    private List<DiscrepancyEvidenceView.CandidateCause> suggestCauses(
            DiscrepancyOrder d, List<DoorSession> sessions, List<OrderRecord> orders,
            List<RestockItem> restockItems, List<LossRecord> losses, List<TempLog> tempLogs) {
        List<DiscrepancyEvidenceView.CandidateCause> out = new ArrayList<>();
        Set<Long> settledSessionIds = new HashSet<>();
        orders.forEach(o -> { if (o.getSession() != null) settledSessionIds.add(o.getSession().getId()); });

        // 1) 温控异常
        List<TempLog> alarms = tempLogs.stream().filter(TempLog::isAlarm).toList();
        if (!alarms.isEmpty()) {
            TempLog first = alarms.get(0);
            double peak = alarms.stream().mapToDouble(TempLog::getTempC).max().orElse(first.getTempC());
            out.add(new DiscrepancyEvidenceView.CandidateCause(
                    DiscrepancyCause.TEMP_DAMAGE.name(), first.getRecordedAt().toString(),
                    "温控曲线越限 " + alarms.size() + " 个采样点", 1,
                    String.format("首次越限 %s，峰值 %.1f℃，需食品安全先行处置并核对损耗数量",
                            first.getRecordedAt(), peak)));
        }

        // 2) 识别漏单 / 销售未扣减：有开门但无有效订单，或挂起/取消会话存在称重净减少
        for (DoorSession s : sessions) {
            boolean charged = settledSessionIds.contains(s.getId());
            Integer weight = s.getWeightDeltaGram();
            if (!charged && weight != null && weight > 0) {
                String cause = s.getStatus() == SessionStatus.REVIEW_PENDING
                        ? DiscrepancyCause.SALE_NOT_DEDUCTED.name()
                        : DiscrepancyCause.RECOGNITION_MISSED.name();
                out.add(new DiscrepancyEvidenceView.CandidateCause(
                        cause, (s.getOpenedAt() != null ? s.getOpenedAt() : d.getWindowStart()).toString(),
                        "开门会话 " + s.getSessionNo() + " 状态 " + s.getStatus(),
                        1, "称重净减少 " + weight + "g 但无有效扣款订单，疑似漏识别/未扣减"));
            }
        }

        // 3) 补货少放：补货明细缺照片或带告警
        for (RestockItem ri : restockItems) {
            if (ri.getPhotoUrl() == null || ri.getWarning() != PlacementWarning.NONE) {
                out.add(new DiscrepancyEvidenceView.CandidateCause(
                        DiscrepancyCause.RESTOCK_SHORTAGE.name(),
                        ri.getCreatedAt().toString(),
                        "补货单 " + ri.getRestockOrder().getOrderNo() + " 货道 " + ri.getActualChannelCode(),
                        1, (ri.getPhotoUrl() == null ? "缺少上架照片；" : "上架告警 " + ri.getWarning() + "；")
                                + "需比对补货照片与申报数量 " + ri.getQty()));
            }
        }

        // 4) 报损漏记：卡货/破损类告警存在但时间窗内无对应报损
        boolean jamAlert = alertRepository.findByCabinetIdOrderByIdDesc(d.getCabinet().getId()).stream()
                .anyMatch(a -> a.getAlertType() == AlertType.CHANNEL_JAM);
        if (jamAlert && losses.isEmpty()) {
            out.add(new DiscrepancyEvidenceView.CandidateCause(
                    DiscrepancyCause.LOSS_MISSED.name(), d.getWindowStart().toString(),
                    "货道卡货告警", 1, "存在卡货告警但核查窗内无报损记录，可能报损漏记"));
        }

        if (out.isEmpty()) {
            out.add(new DiscrepancyEvidenceView.CandidateCause(
                    DiscrepancyCause.UNCONFIRMED.name(), d.getWindowEnd().toString(),
                    "无自动匹配证据", Math.max(1, Math.abs(d.getDiffQty())),
                    "现有证据无法归责，建议挂起复核并补充监控/照片"));
        }
        return out;
    }

    // ============================ 食品安全先行处置 ============================

    /** 建单时若温控曲线越限，隔离该批次在柜可售库存并建立食安待处置单 */
    private void ensureFoodSafetyQuarantine(DiscrepancyOrder d) {
        List<TempLog> temps = tempLogRepository
                .findByCabinetIdAndRecordedAtBetweenOrderByRecordedAtAsc(
                        d.getCabinet().getId(),
                        d.getWindowStart().minusHours(1), d.getWindowEnd().plusHours(1));
        List<TempLog> alarms = temps.stream().filter(TempLog::isAlarm).toList();
        if (alarms.isEmpty()) return;

        int qty = 0;
        for (ChannelStock s : stockRepository.findByCabinetId(d.getCabinet().getId())) {
            if (!s.getBatch().getId().equals(d.getBatch().getId())) continue;
            // 已被温控流程隔离的数量计入受影响量；剩余可售量补隔离
            qty += s.getQtyQuarantined();
            int sellable = s.getQtyOnHand() - s.getQtyQuarantined();
            if (sellable > 0) {
                s.setQtyQuarantined(s.getQtyOnHand());
                stockRepository.save(s);
                qty += sellable;
            }
        }
        if (qty <= 0) return;
        TempLog first = alarms.get(0);
        TempLog last = alarms.get(alarms.size() - 1);
        double peak = alarms.stream().mapToDouble(TempLog::getTempC).max().orElse(first.getTempC());

        FoodSafetyReview review = FoodSafetyReview.builder()
                .reviewNo(genNo("FS"))
                .discrepancy(d).cabinet(d.getCabinet()).batch(d.getBatch()).product(d.getProduct())
                .status(FoodSafetyStatus.PENDING).qty(qty)
                .tempAbuseFrom(first.getRecordedAt()).tempAbuseTo(last.getRecordedAt())
                .peakTempC(peak)
                .remark("差异核查发现温控异常，已先行隔离待安全处置")
                .build();
        foodSafetyRepository.save(review);
        alertService.raise(d.getCabinet(), AlertType.TEMP_ALARM, "CRITICAL",
                String.format("差异单 %s 关联批次 %s 温控异常，%d 件已隔离进入食品安全处置（先行于追责）",
                        d.getDiscrepancyNo(), d.getBatch().getBatchNo(), qty),
                "FOOD_SAFETY", review.getId());
        bizEventPublisher.publish("FOOD_SAFETY_QUARANTINED", review.getReviewNo(),
                d.getCabinet().getCabinetNo(), d.getBatch().getBatchNo(),
                "温控异常隔离 x" + qty, null);
    }

    /**
     * 食品安全处置结论（可在差异单尚未定责时先行执行）。
     * DISPOSED：按实际受损数量报废，记 TEMP_ABUSE 报损并自动确认一条 TEMP_DAMAGE 分摊行；
     * RETURNED：复检合格解除隔离、恢复可售，不产生损耗分摊；
     * RECALLED：退回供应商，解除隔离并出供应商对账调整。
     */
    @Transactional
    public FoodSafetyReview handleFoodSafety(String discrepancyNo, int qty,
                                             FoodSafetyStatus result, Disposition disposition,
                                             String username, String remark) {
        DiscrepancyOrder d = must(discrepancyNo);
        FoodSafetyReview review = foodSafetyRepository
                .findByDiscrepancyIdOrderByCreatedAtDesc(d.getId()).stream()
                .filter(r -> r.getStatus() == FoodSafetyStatus.PENDING).findFirst()
                .orElseThrow(() -> ApiException.conflict("无待处置的食品安全复核单"));
        if (qty <= 0 || qty > review.getQty()) {
            throw ApiException.badRequest("处置数量需在 1~" + review.getQty() + " 之间");
        }
        AppUser handler = userRepository.findByUsername(username).orElse(null);
        review.setStatus(result);
        review.setDisposition(disposition);
        review.setHandledBy(handler);
        review.setHandledAt(LocalDateTime.now());
        review.setRemark(remark);

        switch (result) {
            case DISPOSED -> disposeTempDamage(d, review, qty, handler, remark);
            case RETURNED -> releaseQuarantine(d, qty);
            case RECALLED -> recallToSupplier(d, review, qty, handler, remark);
            default -> throw ApiException.badRequest("不支持的食安处置结果");
        }
        recalcOrder(d, handler);
        return foodSafetyRepository.save(review);
    }

    /** 温控报废：扣隔离库存 + LOSS_OUT + TEMP_ABUSE 报损（运营/平台承担，不计供应商）+ 分摊行 */
    private void disposeTempDamage(DiscrepancyOrder d, FoodSafetyReview review, int qty,
                                   AppUser handler, String remark) {
        deductStock(d, qty);
        BigDecimal cost = cost(d.getBatch(), qty);
        ledgerService.record(d.getBatch(), d.getCabinet(), LedgerType.LOSS_OUT, -qty, cost,
                "FOOD_SAFETY", review.getId(), "温控异常报废 " + review.getReviewNo());
        LossRecord loss = LossRecord.builder()
                .lossNo(genNo("LS")).cabinet(d.getCabinet()).product(d.getProduct()).batch(d.getBatch())
                .reason(LossReason.TEMP_ABUSE).qty(qty).costAmount(cost)
                .liability("OPERATOR").bizType("FOOD_SAFETY").bizId(review.getId())
                .remark("温控异常食品安全处置报废").build();
        lossRepository.save(loss);
        review.setLossRecord(loss);

        DiscrepancyAllocation a = DiscrepancyAllocation.builder()
                .discrepancy(d).cause(DiscrepancyCause.TEMP_DAMAGE)
                .liability(LiabilityParty.FOOD_SAFETY).status(AllocationStatus.CONFIRMED)
                .qty(qty).occurredAt(review.getTempAbuseFrom() != null
                        ? review.getTempAbuseFrom() : LocalDateTime.now())
                .refLossNo(loss.getLossNo()).tempWindow(
                        review.getTempAbuseFrom() + "~" + review.getTempAbuseTo())
                .costAmount(cost).lossRecord(loss)
                .remark("食品安全先行处置报废：" + remark).confirmedAt(LocalDateTime.now())
                .confirmedBy(handler).build();
        allocationRepository.save(a);
        // 保守隔离的其余商品经盘点确认实物完好，解除其隔离（报废数量已扣减）
        int leftover = Math.max(0, review.getQty() - qty);
        if (leftover > 0) {
            releaseQuarantine(d, leftover);
        }
        bizEventPublisher.publish("LOSS_RECORDED", loss.getLossNo(),
                d.getCabinet().getCabinetNo(), d.getBatch().getBatchNo(),
                "温控异常报废 x" + qty, cost);
    }

    /** 复检合格：解除 qty 件隔离恢复可售 */
    private void releaseQuarantine(DiscrepancyOrder d, int qty) {
        int remain = qty;
        for (ChannelStock s : stockRepository.findByCabinetId(d.getCabinet().getId())) {
            if (remain <= 0) break;
            if (!s.getBatch().getId().equals(d.getBatch().getId())) continue;
            int quarantined = s.getQtyQuarantined();
            int take = Math.min(remain, quarantined);
            if (take > 0) {
                s.setQtyQuarantined(quarantined - take);
                stockRepository.save(s);
                remain -= take;
            }
        }
    }

    /** 召回退供：实物离开柜机（扣库存但不记运营报损），对账按供应商召回处理 */
    private void recallToSupplier(DiscrepancyOrder d, FoodSafetyReview review, int qty,
                                  AppUser handler, String remark) {
        deductStock(d, qty);
        BigDecimal cost = cost(d.getBatch(), qty);
        ledgerService.record(d.getBatch(), d.getCabinet(), LedgerType.REMOVAL_OUT, -qty, cost,
                "FOOD_SAFETY", review.getId(), "温控异常召回退供 " + review.getReviewNo());
        ReconAdjustment adj = ReconAdjustment.builder()
                .adjustmentNo(genNo("RA")).discrepancy(d)
                .supplier(d.getBatch().getSupplier()).batch(d.getBatch())
                .direction(ReconDirection.DEDUCT).qty(qty).amount(cost)
                .remark("温控异常召回退供：" + remark).build();
        reconRepository.save(adj);
    }

    // ============================ 拆分定责 ============================

    /**
     * 提交一批分摊行（按发生时间+证据拆分）。
     * cause=UNCONFIRMED 的行挂起复核（SUSPENDED），其余确认并立即记账/分流。
     * 只允许在 OPEN / PARTIALLY_CONFIRMED / REOPENED 状态提交。
     */
    @Transactional
    public List<DiscrepancyAllocation> allocate(String discrepancyNo, List<AllocateLine> lines,
                                                String username) {
        DiscrepancyOrder d = must(discrepancyNo);
        if (d.getStatus() == DiscrepancyStatus.RESOLVED) {
            throw ApiException.conflict("差异单已结单，请先因新证据重新打开后再改判");
        }
        AppUser handler = userRepository.findByUsername(username).orElse(null);
        List<DiscrepancyAllocation> saved = new ArrayList<>();
        for (AllocateLine line : lines) {
            saved.add(createAllocation(d, line, handler, null));
        }
        recalcOrder(d, handler);
        return saved;
    }

    private DiscrepancyAllocation createAllocation(DiscrepancyOrder d, AllocateLine line,
                                                   AppUser handler,
                                                   DiscrepancyAllocation reversed) {
        if (line.qty() == null || line.qty() == 0) {
            throw ApiException.badRequest("分摊数量不能为 0");
        }
        boolean shortage = d.getDiffQty() >= 0;
        if (shortage && line.qty() < 0) {
            throw ApiException.badRequest("盘亏单分摊数量应为正");
        }
        if (!shortage && line.qty() > 0) {
            throw ApiException.badRequest("盘盈单分摊数量应为负");
        }
        // 累计数量不得超过总差异
        int allocated = allocationRepository.findByDiscrepancyIdOrderByOccurredAtAscIdAsc(d.getId()).stream()
                .filter(a -> a.getStatus() != AllocationStatus.VOIDED)
                .mapToInt(DiscrepancyAllocation::getQty).sum();
        if (Math.abs(allocated + line.qty()) > Math.abs(d.getDiffQty())) {
            throw ApiException.badRequest(String.format(
                    "分摊累计 %d 超过差异总数 %d，请核对每笔数量",
                    Math.abs(allocated + line.qty()), Math.abs(d.getDiffQty())));
        }

        DiscrepancyCause cause = line.cause() != null ? line.cause() : DiscrepancyCause.UNCONFIRMED;
        boolean suspended = cause == DiscrepancyCause.UNCONFIRMED;
        LocalDateTime occurred = line.occurredAt() != null ? line.occurredAt()
                : (suspended ? d.getWindowEnd() : LocalDateTime.now());

        DiscrepancyAllocation a = DiscrepancyAllocation.builder()
                .discrepancy(d).cause(cause)
                .liability(suspended ? LiabilityParty.NONE : line.liability())
                .status(suspended ? AllocationStatus.SUSPENDED : AllocationStatus.CONFIRMED)
                .qty(line.qty()).occurredAt(occurred)
                .refOrderNo(line.refOrderNo()).refSessionNo(line.refSessionNo())
                .refRestockNo(line.refRestockNo()).refLossNo(line.refLossNo())
                .refRemovalNo(line.refRemovalNo())
                .costAmount(cost(d.getBatch(), Math.abs(line.qty())))
                .remark(line.remark())
                .reversedAllocation(reversed)
                .confirmedAt(suspended ? null : LocalDateTime.now())
                .confirmedBy(suspended ? null : handler)
                .build();
        allocationRepository.save(a);

        if (!suspended) {
            applyLiability(d, a, handler);
        }
        return a;
    }

    /** 确认一行的库存/台账记账与责任分流 */
    private void applyLiability(DiscrepancyOrder d, DiscrepancyAllocation a, AppUser handler) {
        int qty = a.getQty();
        BigDecimal amount = a.getCostAmount() != null ? a.getCostAmount() : cost(d.getBatch(), Math.abs(qty));

        if (a.getCause() == DiscrepancyCause.OVERCOUNT) {
            // 盘盈：回补库存 + ADJUST_PLUS，无罚则
            addStock(d, Math.abs(qty));
            ledgerService.record(d.getBatch(), d.getCabinet(), LedgerType.ADJUST_PLUS, Math.abs(qty),
                    amount, "DISCREPANCY", d.getId(),
                    "差异单 " + d.getDiscrepancyNo() + " 盘盈回补");
            return;
        }

        // 盘亏类：扣库存（食安已先行扣减的 TEMP_DAMAGE 不重复扣）
        if (a.getCause() != DiscrepancyCause.TEMP_DAMAGE) {
            deductStock(d, qty);
        }

        switch (a.getCause()) {
            case RESTOCK_SHORTAGE -> {
                ledgerService.record(d.getBatch(), d.getCabinet(), LedgerType.ADJUST_MINUS, -qty,
                        amount, "DISCREPANCY", d.getId(),
                        "差异单 " + d.getDiscrepancyNo() + " 补货少放/到货短少定责");
                if (a.getLiability() == LiabilityParty.SUPPLIER) {
                    saveLoss(a, d, LossReason.RESTOCK_SHORTAGE, amount, "SUPPLIER");
                    reconRepository.save(ReconAdjustment.builder()
                            .adjustmentNo(genNo("RA")).discrepancy(d)
                            .supplier(d.getBatch().getSupplier()).batch(d.getBatch()).allocation(a)
                            .direction(ReconDirection.DEDUCT).qty(qty).amount(amount)
                            .remark("补货/到货短少，供应商账单扣减").build());
                } else {
                    // 默认补货员责任：报损挂补货员 + 结算扣罚 + 评分
                    saveLoss(a, d, LossReason.RESTOCK_SHORTAGE, amount, "REPLENISHER");
                    AppUser replenisher = resolveReplenisher(d, handler);
                    if (replenisher != null) {
                        penaltyRepository.save(ReplenisherPenalty.builder()
                                .penaltyNo(genNo("RP")).discrepancy(d).replenisher(replenisher)
                                .batch(d.getBatch()).allocation(a).qty(qty)
                                .deductAmount(amount)
                                .scorePoints(qty * SCORE_POINTS_PER_QTY)
                                .status(PenaltyStatus.PENDING)
                                .remark("补货少放定责，待补货员结算扣款").build());
                        replenisher.setScorePoints(Math.max(0,
                                (replenisher.getScorePoints() == null ? 100 : replenisher.getScorePoints())
                                        - qty * SCORE_POINTS_PER_QTY));
                        userRepository.save(replenisher);
                    }
                }
            }
            case SALE_NOT_DEDUCTED, RECOGNITION_MISSED -> {
                LossReason lr = a.getCause() == DiscrepancyCause.SALE_NOT_DEDUCTED
                        ? LossReason.SALE_NOT_DEDUCTED : LossReason.RECOGNITION_MISSED;
                ledgerService.record(d.getBatch(), d.getCabinet(), LedgerType.ADJUST_MINUS, -qty,
                        amount, "DISCREPANCY", d.getId(),
                        "差异单 " + d.getDiscrepancyNo() + " " + a.getCause());
                saveLoss(a, d, lr, amount, "PLATFORM");
                // 识别算法样本回流（关联开门会话与视频证据）
                DoorSession session = a.getRefSessionNo() != null
                        ? sessionRepository.findBySessionNo(a.getRefSessionNo()).orElse(null) : null;
                String evidenceRef = session != null
                        ? ("session=" + session.getSessionNo()
                            + (recognitionEventsClip(session))) : a.getRefOrderNo();
                sampleRepository.save(RecognitionSample.builder()
                        .sampleNo(genNo("RS-SAMPLE")).discrepancy(d).session(session)
                        .batch(d.getBatch()).allocation(a).qty(qty)
                        .label(a.getCause().name()).evidenceRef(evidenceRef).build());
            }
            case LOSS_MISSED -> {
                ledgerService.record(d.getBatch(), d.getCabinet(), LedgerType.LOSS_OUT, -qty,
                        amount, "DISCREPANCY", d.getId(),
                        "差异单 " + d.getDiscrepancyNo() + " 报损漏记补登");
                String liability = a.getLiability() == LiabilityParty.SUPPLIER ? "SUPPLIER" : "OPERATOR";
                LossRecord loss = saveLoss(a, d, LossReason.SHRINKAGE, amount, liability);
                if ("SUPPLIER".equals(liability)) {
                    reconRepository.save(ReconAdjustment.builder()
                            .adjustmentNo(genNo("RA")).discrepancy(d)
                            .supplier(d.getBatch().getSupplier()).batch(d.getBatch()).allocation(a)
                            .direction(ReconDirection.DEDUCT).qty(qty).amount(amount)
                            .remark("报损漏记补登，供应商责任扣减").build());
                }
            }
            case TEMP_DAMAGE -> {
                // 食安先行已记账与报损，这里仅兜底（未走食安直接定责的情形）
                if (a.getLossRecord() == null) {
                    LossRecord loss = saveLoss(a, d, LossReason.TEMP_ABUSE, amount, "OPERATOR");
                    ledgerService.record(d.getBatch(), d.getCabinet(), LedgerType.LOSS_OUT, -qty,
                            amount, "DISCREPANCY", d.getId(),
                            "差异单 " + d.getDiscrepancyNo() + " 温控损耗定责");
                    a.setLossRecord(loss);
                }
            }
            default -> throw ApiException.badRequest("不支持的差异原因: " + a.getCause());
        }
    }

    private String recognitionEventsClip(DoorSession session) {
        List<RecognitionEvent> evs =
                recognitionEventRepository.findBySessionIdOrderByDetectedAtAsc(session.getId());
        return evs.stream().map(e -> e.getVideoClipUrl() != null ? e.getVideoClipUrl() : "")
                .filter(s -> !s.isBlank()).findFirst().map(u -> ",clip=" + u).orElse("");
    }

    private LossRecord saveLoss(DiscrepancyAllocation a, DiscrepancyOrder d, LossReason reason,
                                BigDecimal amount, String liability) {
        LossRecord loss = LossRecord.builder()
                .lossNo(genNo("LS")).cabinet(d.getCabinet()).product(d.getProduct()).batch(d.getBatch())
                .reason(reason).qty(Math.abs(a.getQty())).costAmount(amount)
                .liability(liability).bizType("DISCREPANCY").bizId(d.getId())
                .remark(d.getDiscrepancyNo() + " " + a.getCause()
                        + (a.getRemark() != null ? " " + a.getRemark() : "")).build();
        lossRepository.save(loss);
        a.setLossRecord(loss);
        return loss;
    }

    /** 优先取跨班次接回/补货单中的补货员，否则取建单人（若为补货员），否则不挂个人罚则 */
    private AppUser resolveReplenisher(DiscrepancyOrder d, AppUser fallback) {
        List<RestockItem> items = restockItemRepository.findByBatchId(d.getBatch().getId()).stream()
                .filter(i -> i.getChannel() != null
                        && i.getChannel().getCabinet().getId().equals(d.getCabinet().getId()))
                .toList();
        for (int i = items.size() - 1; i >= 0; i--) {
            AppUser r = items.get(i).getRestockOrder().getReplenisher();
            if (r != null) return r;
        }
        if (fallback != null && "REPLENISHER".equals(fallback.getRole())) return fallback;
        return null;
    }

    // ============================ 结单 / 状态重算 ============================

    /**
     * 重算差异单数量与状态：全部确认且无挂起 → RESOLVED 解冻；
     * 有挂起 → PARTIALLY_CONFIRMED 维持冻结；全部尚未处理 → OPEN。
     */
    private void recalcOrder(DiscrepancyOrder d, AppUser handler) {
        List<DiscrepancyAllocation> all =
                allocationRepository.findByDiscrepancyIdOrderByOccurredAtAscIdAsc(d.getId());
        List<DiscrepancyAllocation> live = all.stream()
                .filter(a -> a.getStatus() != AllocationStatus.VOIDED).toList();
        int confirmed = live.stream().filter(a -> a.getStatus() == AllocationStatus.CONFIRMED)
                .mapToInt(a -> Math.abs(a.getQty())).sum();
        int suspended = live.stream().filter(a -> a.getStatus() == AllocationStatus.SUSPENDED)
                .mapToInt(a -> Math.abs(a.getQty())).sum();
        int voided = all.stream().filter(a -> a.getStatus() == AllocationStatus.VOIDED)
                .mapToInt(a -> Math.abs(a.getQty())).sum();
        d.setConfirmedQty(confirmed);
        d.setSuspendedQty(suspended);
        d.setVoidedQty(voided);
        d.setHandledBy(handler);

        int liveQty = live.stream().mapToInt(a -> Math.abs(a.getQty())).sum();
        boolean fullyAllocated = liveQty == Math.abs(d.getDiffQty());

        if (fullyAllocated && suspended == 0) {
            d.setStatus(DiscrepancyStatus.CONFIRMED);
            doResolve(d, handler);
        } else if (liveQty > 0) {
            d.setStatus(DiscrepancyStatus.PARTIALLY_CONFIRMED);
            d.setSettlementFrozen(true);
        } else {
            d.setStatus(DiscrepancyStatus.OPEN);
        }
        discrepancyRepository.save(d);
    }

    /** 全部定责完成：解冻批次结算/对账，盘点单收口，发出闭环事件 */
    private void doResolve(DiscrepancyOrder d, AppUser handler) {
        d.setStatus(DiscrepancyStatus.RESOLVED);
        d.setSettlementFrozen(false);
        d.setUnfrozenAt(LocalDateTime.now());
        d.setResolvedAt(LocalDateTime.now());

        // 盘点单收口，避免再走旧的"一刀切盘亏"调整造成重复扣减
        if (d.getStocktake() != null && d.getStocktake().getStatus() != StocktakeStatus.ADJUSTED) {
            StocktakeOrder st = d.getStocktake();
            st.setStatus(StocktakeStatus.ADJUSTED);
            st.setRemark((st.getRemark() == null ? "" : st.getRemark() + "；")
                    + "差异由核查单 " + d.getDiscrepancyNo() + " 拆分定责调整");
            stocktakeRepository.save(st);
        }
        bizEventPublisher.publish("DISCREPANCY_RESOLVED", d.getDiscrepancyNo(),
                d.getCabinet().getCabinetNo(), d.getBatch().getBatchNo(),
                "差异全部定责，批次结算解冻", null);
    }

    /** 手动尝试结单（全部差异已分摊且无挂起时生效） */
    @Transactional
    public DiscrepancyOrder resolve(String discrepancyNo, String username) {
        DiscrepancyOrder d = must(discrepancyNo);
        AppUser handler = userRepository.findByUsername(username).orElse(null);
        recalcOrder(d, handler);
        if (d.getStatus() != DiscrepancyStatus.RESOLVED) {
            throw ApiException.conflict(String.format(
                    "尚有差异未定责：已确认 %d，挂起 %d，总差异 %d，不能结单",
                    d.getConfirmedQty(), d.getSuspendedQty(), Math.abs(d.getDiffQty())));
        }
        return d;
    }

    // ============================ 改判 ============================

    /**
     * 改判：作废原分摊行（冲销其库存/台账/报损/罚则），用新原因/责任重新定责。
     * 实物已销毁的温控报废不回补库存，仅调整责任与对账。
     */
    @Transactional
    public DiscrepancyAllocation rejudge(String discrepancyNo, Long allocationId,
                                         AllocateLine newline, String username) {
        DiscrepancyOrder d = must(discrepancyNo);
        if (d.getStatus() == DiscrepancyStatus.RESOLVED) {
            d.setStatus(DiscrepancyStatus.REOPENED);
            d.setSettlementFrozen(true);
            d.setFrozenAt(LocalDateTime.now());
            d.setUnfrozenAt(null);
            d.setResolvedAt(null);
            discrepancyRepository.save(d);
        }
        DiscrepancyAllocation old = allocationRepository.findById(allocationId)
                .orElseThrow(() -> ApiException.notFound("分摊行不存在"));
        if (!old.getDiscrepancy().getId().equals(d.getId())) {
            throw ApiException.badRequest("分摊行不属于该差异单");
        }
        if (old.getStatus() == AllocationStatus.VOIDED) {
            throw ApiException.conflict("该分摊行已作废，不能再次改判");
        }
        AppUser handler = userRepository.findByUsername(username).orElse(null);
        reverseAllocation(d, old, handler);
        DiscrepancyAllocation created = createAllocation(d, newline, handler, old);
        recalcOrder(d, handler);
        return created;
    }

    /** 冲销一条已确认分摊行的库存/台账/报损/罚则；挂起行未动过账，仅作废 */
    private void reverseAllocation(DiscrepancyOrder d, DiscrepancyAllocation a, AppUser handler) {
        if (a.getStatus() == AllocationStatus.SUSPENDED) {
            a.setStatus(AllocationStatus.VOIDED);
            a.setRemark((a.getRemark() == null ? "" : a.getRemark() + "；")
                    + "挂起行随改判作废 by " + (handler != null ? handler.getUsername() : "system"));
            allocationRepository.save(a);
            if (!d.isSettlementFrozen()) {
                d.setSettlementFrozen(true);
                d.setFrozenAt(LocalDateTime.now());
                d.setUnfrozenAt(null);
                d.setResolvedAt(null);
            }
            return;
        }

        int qty = Math.abs(a.getQty());
        boolean physicalDestroyed = a.getCause() == DiscrepancyCause.TEMP_DAMAGE
                && a.getLossRecord() != null;

        if (!physicalDestroyed && a.getCause() != DiscrepancyCause.OVERCOUNT) {
            // 盘亏类回补库存
            addStock(d, qty);
        }
        if (a.getCause() == DiscrepancyCause.OVERCOUNT) {
            // 盘盈改判：撤回回补
            deductStock(d, qty);
        }

        // 反向台账（实物已销毁的温控损耗不回补库存，仅做金额/责任冲销说明）
        BigDecimal amount = a.getCostAmount() != null ? a.getCostAmount() : cost(d.getBatch(), qty);
        if (!physicalDestroyed) {
            LedgerType reverse = switch (a.getCause()) {
                case OVERCOUNT -> LedgerType.ADJUST_MINUS;
                case LOSS_MISSED, TEMP_DAMAGE -> LedgerType.RESTOCK_IN; // 回补损耗出库
                default -> LedgerType.ADJUST_PLUS;
            };
            int change = a.getCause() == DiscrepancyCause.OVERCOUNT ? -qty : qty;
            ledgerService.record(d.getBatch(), d.getCabinet(), reverse, change, amount,
                    "DISCREPANCY_REVERSE", d.getId(),
                    "改判冲销分摊行 " + a.getId() + " (" + a.getCause() + ")");
        }

        // 作废报损
        if (a.getLossRecord() != null) {
            LossRecord loss = a.getLossRecord();
            loss.setVoided(true);
            loss.setVoidRemark("差异单 " + d.getDiscrepancyNo() + " 改判冲销");
            lossRepository.save(loss);
        }

        // 撤回补货员罚则与评分（已结算扣款保留记录，标记 REVERSED 待下期补）
        for (ReplenisherPenalty p : penaltyRepository.findByDiscrepancyId(d.getId())) {
            if (p.getAllocation() != null && p.getAllocation().getId().equals(a.getId())
                    && p.getStatus() != PenaltyStatus.REVERSED) {
                boolean alreadySettled = p.getStatus() == PenaltyStatus.DEDUCTED;
                p.setStatus(PenaltyStatus.REVERSED);
                p.setRemark((p.getRemark() == null ? "" : p.getRemark() + "；")
                        + "改判冲销" + (alreadySettled ? "（已扣款，下期补回）" : "（未结算，直接撤回）"));
                penaltyRepository.save(p);
                AppUser r = p.getReplenisher();
                if (r != null) {
                    r.setScorePoints(Math.min(100,
                            (r.getScorePoints() == null ? 100 : r.getScorePoints())
                                    + (p.getScorePoints() == null ? 0 : p.getScorePoints())));
                    userRepository.save(r);
                }
            }
        }

        // 供应商对账调整：未核销直接作废；已核销则出反向调整
        for (ReconAdjustment adj : reconRepository.findByDiscrepancyId(d.getId())) {
            if (adj.getAllocation() != null && adj.getAllocation().getId().equals(a.getId())
                    && !adj.isVoided()) {
                if (!adj.isApplied()) {
                    adj.setVoided(true);
                    adj.setRemark((adj.getRemark() == null ? "" : adj.getRemark() + "；")
                            + "改判作废");
                    reconRepository.save(adj);
                } else {
                    reconRepository.save(ReconAdjustment.builder()
                            .adjustmentNo(genNo("RA")).discrepancy(d)
                            .supplier(adj.getSupplier()).batch(adj.getBatch())
                            .direction(adj.getDirection() == ReconDirection.DEDUCT
                                    ? ReconDirection.ADD : ReconDirection.DEDUCT)
                            .qty(adj.getQty()).amount(adj.getAmount())
                            .remark("改判反向冲销已核销调整 " + adj.getAdjustmentNo()).build());
                }
            }
        }

        // 识别样本保留（对模型仍有价值），仅标注改判
        for (RecognitionSample s : sampleRepository.findByDiscrepancyId(d.getId())) {
            if (s.getAllocation() != null && s.getAllocation().getId().equals(a.getId()) && !s.isReviewed()) {
                s.setReviewNote("原判定 " + a.getCause() + " 已改判，样本保留供误判分析");
                sampleRepository.save(s);
            }
        }

        a.setStatus(AllocationStatus.VOIDED);
        a.setRemark((a.getRemark() == null ? "" : a.getRemark() + "；")
                + "改判作废 by " + (handler != null ? handler.getUsername() : "system"));
        allocationRepository.save(a);

        // 解冻状态回到未结
        if (!d.isSettlementFrozen()) {
            d.setSettlementFrozen(true);
            d.setFrozenAt(LocalDateTime.now());
            d.setUnfrozenAt(null);
            d.setResolvedAt(null);
        }
    }

    // ============================ 后续接回（补传/重复盘点/跨班次/已结算订单） ============================

    /** 把事后证据或业务接回原差异单；必要时重开核查 */
    @Transactional
    public DiscrepancyFollowUp attachFollowUp(String discrepancyNo, FollowUpType type, String refNo,
                                              String photoUrls, Integer qtyChange,
                                              String username, String remark) {
        DiscrepancyOrder d = must(discrepancyNo);
        AppUser user = userRepository.findByUsername(username).orElse(null);
        DiscrepancyFollowUp fu = DiscrepancyFollowUp.builder()
                .discrepancy(d).type(type).refNo(refNo).photoUrls(photoUrls)
                .qtyChange(qtyChange == null ? 0 : qtyChange)
                .submittedBy(user).happenedAt(LocalDateTime.now()).remark(remark)
                .build();
        followUpRepository.save(fu);

        // 已结单再接回实质证据 → 重开，批次重新冻结，等待改判
        if (d.getStatus() == DiscrepancyStatus.RESOLVED) {
            reopen(d, null, username, "因" + type + "接回（" + refNo + "）重新核查");
        }
        bizEventPublisher.publish("DISCREPANCY_FOLLOWUP", d.getDiscrepancyNo(),
                d.getCabinet().getCabinetNo(), d.getBatch().getBatchNo(),
                type + " 接回差异单 ref=" + refNo, null);
        return fu;
    }

    /**
     * 重复盘点：以新的实盘数量接回原差异单。
     * 新实盘 != 原实盘时差异数量变更，已确认/挂起分摊自动冲销作废（库存/台账/罚则回滚，
     * 温控已销毁实物不回补），运营按新差异重新拆分；全程留痕。
     */
    @Transactional
    public DiscrepancyOrder recount(String discrepancyNo, int newCountedQty, String username, String remark) {
        DiscrepancyOrder d = must(discrepancyNo);
        AppUser handler = userRepository.findByUsername(username).orElse(null);
        int oldCounted = d.getCountedQty() == null ? 0 : d.getCountedQty();
        followUpRepository.save(DiscrepancyFollowUp.builder()
                .discrepancy(d).type(FollowUpType.RECOUNT)
                .refNo(null).qtyChange(newCountedQty - oldCounted)
                .submittedBy(handler)
                .happenedAt(LocalDateTime.now())
                .remark("重复盘点，新实盘 " + newCountedQty).build());

        boolean resolved = d.getStatus() == DiscrepancyStatus.RESOLVED;
        if (newCountedQty != oldCounted || resolved) {
            // 差异变化或结单后重盘：冲销所有在效分摊，回滚到待核查
            for (DiscrepancyAllocation a :
                    allocationRepository.findByDiscrepancyIdOrderByOccurredAtAscIdAsc(d.getId())) {
                if (a.getStatus() != AllocationStatus.VOIDED) {
                    reverseAllocation(d, a, handler);
                }
            }
            d.setCountedQty(newCountedQty);
            d.setDiffQty(d.getSystemQty() - newCountedQty);
            d.setWindowEnd(LocalDateTime.now());
            d.setConfirmedQty(0);
            d.setSuspendedQty(0);
            d.setVoidedQty(allocationRepository
                    .findByDiscrepancyIdOrderByOccurredAtAscIdAsc(d.getId()).stream()
                    .filter(a -> a.getStatus() == AllocationStatus.VOIDED)
                    .mapToInt(a -> Math.abs(a.getQty())).sum());
            reopen(d, null, username, "重复盘点实盘由 " + oldCounted + " 变为 " + newCountedQty
                    + (remark != null ? "：" + remark : ""));
        } else {
            discrepancyRepository.save(d);
        }
        return d;
    }

    private void reopen(DiscrepancyOrder d, Integer newCountedQty, String username, String remark) {
        d.setStatus(DiscrepancyStatus.REOPENED);
        d.setSettlementFrozen(true);
        d.setFrozenAt(LocalDateTime.now());
        d.setUnfrozenAt(null);
        d.setResolvedAt(null);
        d.setHandledBy(userRepository.findByUsername(username).orElse(null));
        if (newCountedQty != null) {
            d.setCountedQty(newCountedQty);
            d.setDiffQty(d.getSystemQty() - newCountedQty);
        }
        d.setRemark((d.getRemark() == null ? "" : d.getRemark() + "；") + remark);
        discrepancyRepository.save(d);
        alertService.raise(d.getCabinet(), AlertType.STOCK_DISCREPANCY, "WARN",
                "差异核查单 " + d.getDiscrepancyNo() + " 重新打开：" + remark,
                "DISCREPANCY", d.getId());
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public DiscrepancyOrder must(String no) {
        return discrepancyRepository.findByDiscrepancyNo(no)
                .orElseThrow(() -> ApiException.notFound("差异核查单不存在: " + no));
    }

    @Transactional(readOnly = true)
    public List<DiscrepancyAllocation> allocations(String discrepancyNo) {
        DiscrepancyOrder d = must(discrepancyNo);
        return allocationRepository.findByDiscrepancyIdOrderByOccurredAtAscIdAsc(d.getId());
    }

    @Transactional(readOnly = true)
    public List<DiscrepancyFollowUp> followUps(String discrepancyNo) {
        DiscrepancyOrder d = must(discrepancyNo);
        return followUpRepository.findByDiscrepancyIdOrderByCreatedAtAsc(d.getId());
    }

    @Transactional(readOnly = true)
    public List<FoodSafetyReview> foodSafetyReviews(String discrepancyNo) {
        DiscrepancyOrder d = must(discrepancyNo);
        return foodSafetyRepository.findByDiscrepancyIdOrderByCreatedAtDesc(d.getId());
    }

    // ============================ 补货员结算 / 算法样本（责任分流下游） ============================

    /** 补货员待结算扣罚列表（评分在定责时已即时计入） */
    @Transactional(readOnly = true)
    public List<ReplenisherPenalty> pendingPenalties(Long replenisherId) {
        return replenisherId == null
                ? penaltyRepository.findByStatus(PenaltyStatus.PENDING)
                : penaltyRepository.findByReplenisherIdAndStatus(replenisherId, PenaltyStatus.PENDING);
    }

    /**
     * 补货员结算：把该补货员全部待扣罚差异责任一次性扣款入账（状态 PENDING -> DEDUCTED），
     * 返回扣款汇总。改判无责的罚则在改判时已 REVERSED，不会进入结算。
     */
    @Transactional
    public java.util.Map<String, Object> settleReplenisher(Long replenisherId, String operatorName) {
        AppUser replenisher = userRepository.findById(replenisherId)
                .orElseThrow(() -> ApiException.notFound("补货员不存在"));
        List<ReplenisherPenalty> pending =
                penaltyRepository.findByReplenisherIdAndStatus(replenisherId, PenaltyStatus.PENDING);
        if (pending.isEmpty()) {
            throw ApiException.conflict("该补货员无待结算的差异扣罚");
        }
        String settleNo = genNo("RPSTL");
        LocalDateTime now = LocalDateTime.now();
        BigDecimal totalDeduct = BigDecimal.ZERO;
        int totalQty = 0;
        for (ReplenisherPenalty p : pending) {
            p.setStatus(PenaltyStatus.DEDUCTED);
            p.setSettledInNo(settleNo);
            p.setSettledAt(now);
            penaltyRepository.save(p);
            totalDeduct = totalDeduct.add(p.getDeductAmount() != null ? p.getDeductAmount() : BigDecimal.ZERO);
            totalQty += p.getQty() == null ? 0 : p.getQty();
            bizEventPublisher.publish("REPLENISHER_DEDUCTED", settleNo,
                    p.getDiscrepancy().getCabinet().getCabinetNo(),
                    p.getBatch() != null ? p.getBatch().getBatchNo() : null,
                    "补货员 " + replenisher.getUsername() + " 差异扣罚 " + p.getDeductAmount(),
                    p.getDeductAmount());
        }
        return java.util.Map.of(
                "settlementNo", settleNo,
                "replenisher", replenisher.getUsername(),
                "penaltyCount", pending.size(),
                "totalQty", totalQty,
                "totalDeductAmount", totalDeduct,
                "scorePoints", replenisher.getScorePoints() == null ? 100 : replenisher.getScorePoints(),
                "operator", operatorName == null ? "" : operatorName);
    }

    /** 待算法团队复核的识别样本 */
    @Transactional(readOnly = true)
    public List<RecognitionSample> pendingSamples() {
        return sampleRepository.findByReviewedFalse();
    }

    /** 算法团队复核样本（采纳回流/误判标注） */
    @Transactional
    public RecognitionSample reviewSample(Long sampleId, String note) {
        RecognitionSample s = sampleRepository.findById(sampleId)
                .orElseThrow(() -> ApiException.notFound("样本不存在"));
        s.setReviewed(true);
        s.setReviewNote(note);
        s.setReviewedAt(LocalDateTime.now());
        return sampleRepository.save(s);
    }

    // ============================ 库存/金额辅助 ============================

    /** 盘亏定责扣减：优先差异货道+批次，不足在本柜同批次其他货道扣 */
    private void deductStock(DiscrepancyOrder d, int qty) {
        int remain = qty;
        List<ChannelStock> stocks = stockRepository.findByBatchId(d.getBatch().getId()).stream()
                .filter(s -> s.getChannel().getCabinet().getId().equals(d.getCabinet().getId()))
                .sorted((x, y) -> {
                    boolean xMatch = d.getChannel() != null
                            && x.getChannel().getId().equals(d.getChannel().getId());
                    boolean yMatch = d.getChannel() != null
                            && y.getChannel().getId().equals(d.getChannel().getId());
                    return Boolean.compare(!xMatch, !yMatch);
                })
                .toList();
        for (ChannelStock s : stocks) {
            if (remain <= 0) break;
            int available = s.getQtyOnHand();
            int take = Math.min(remain, available);
            if (take <= 0) continue;
            s.setQtyOnHand(available - take);
            s.setQtyQuarantined(Math.max(0, s.getQtyQuarantined() - take));
            stockRepository.save(s);
            remain -= take;
        }
        if (remain > 0) {
            throw ApiException.conflict("批次 " + d.getBatch().getBatchNo()
                    + " 在柜库存不足，无法定责扣减 " + remain + " 件（可能已被其他流程出库）");
        }
    }

    private void addStock(DiscrepancyOrder d, int qty) {
        ChannelStock target = null;
        if (d.getChannel() != null) {
            target = stockRepository
                    .findByChannelIdAndBatchId(d.getChannel().getId(), d.getBatch().getId()).orElse(null);
        }
        if (target == null) {
            target = stockRepository.findByBatchId(d.getBatch().getId()).stream()
                    .filter(s -> s.getChannel().getCabinet().getId().equals(d.getCabinet().getId()))
                    .findFirst().orElse(null);
        }
        if (target == null) {
            throw ApiException.conflict("批次在该柜机已无货道库存行，无法回补，请人工建库存");
        }
        target.setQtyOnHand(target.getQtyOnHand() + qty);
        stockRepository.save(target);
    }

    private BigDecimal cost(ProductBatch batch, int qty) {
        BigDecimal unit = batch.getCostPrice() != null ? batch.getCostPrice() : BigDecimal.ZERO;
        return unit.multiply(BigDecimal.valueOf(qty));
    }

    private String genNo(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }

    /** 拆分定责入参 */
    public record AllocateLine(
            DiscrepancyCause cause,
            LiabilityParty liability,
            Integer qty,
            LocalDateTime occurredAt,
            String refOrderNo,
            String refSessionNo,
            String refRestockNo,
            String refLossNo,
            String refRemovalNo,
            String remark) {
    }
}
