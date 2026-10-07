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
import java.util.ArrayList;
import java.util.List;

/**
 * 盘点差异调查服务。
 *
 * 盘点发现系统库存与实盘不符时，围绕差额查清楚是：
 * 销售没有扣减 / 补货时少放 / 报损漏记 / 识别漏单 / 冷藏温度异常损耗。
 *
 * 核心规则：
 * 1. 差异单关联补货前后库存、补货照片、销售订单、开门记录、报损记录、温控曲线；
 * 2. 同一时间段有多种可能时，按发生时间与证据把数量拆到不同成因，
 *    不把全部差额记到补货员名下；无法确认的数量挂起复核（PENDING）；
 * 3. 差异查清前只冻结相关批次的补货结算与供应商对账（batch.settlementFrozen），
 *    其他商品/批次照常结算；
 * 4. 确认责任后：补货员结算与评分、供应商账单、识别算法样本、食品安全复核各走各的流程；
 *    温度异常商品先行安全处置，不等普通追责结束；
 * 5. 补传照片、重复盘点、跨班次补货、已结算订单都接回原差异单，
 *    每次数量与责任变化写 DiscrepancyTrace；改判冲销旧拆账条目、重建新条目。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscrepancyService {

    /** 补货员责任每件扣评分 */
    private static final int SCORE_POINTS_PER_UNIT = 2;

    private final DiscrepancyOrderRepository discrepancyRepository;
    private final DiscrepancyCauseItemRepository causeRepository;
    private final DiscrepancyFollowUpRepository followUpRepository;
    private final DiscrepancyEvidenceRepository evidenceRepository;
    private final DiscrepancyTraceRepository traceRepository;
    private final StocktakeOrderRepository stocktakeRepository;
    private final StocktakeItemRepository stocktakeItemRepository;
    private final CabinetRepository cabinetRepository;
    private final ChannelRepository channelRepository;
    private final AppUserRepository userRepository;
    private final ProductBatchRepository batchRepository;
    private final ChannelStockRepository stockRepository;
    private final BatchLedgerRepository ledgerRepository;
    private final RestockOrderRepository restockOrderRepository;
    private final RestockItemRepository restockItemRepository;
    private final DoorSessionRepository sessionRepository;
    private final OrderRecordRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final RecognitionEventRepository recognitionEventRepository;
    private final LossRecordRepository lossRepository;
    private final TempLogRepository tempLogRepository;
    private final BatchLedgerService ledgerService;
    private final BizEventPublisher bizEventPublisher;

    // ---------------------------------------------------------------------
    // 创建 / 冻结 / 证据装配
    // ---------------------------------------------------------------------

    /**
     * 盘点录入差异后为某个盘亏行确保存在差异单（幂等：重复盘点不新建，接回原单）。
     * 创建即冻结该批次的补货结算与供应商对账，并预装配时间窗内全部证据。
     */
    @Transactional
    public DiscrepancyOrder ensureForStocktakeItem(StocktakeItem item) {
        StocktakeOrder st = item.getStocktake();
        return createInternal(st, item.getChannel(), item.getProduct(), item.getBatch(),
                item.getSystemQty(), item.getCountedQty(), null);
    }

    /** 手工从盘点单某行发起差异调查 */
    @Transactional
    public DiscrepancyOrder openFromStocktake(String stocktakeNo, String channelCode,
                                              String batchNo, String operatorUsername) {
        StocktakeOrder st = stocktakeRepository.findByOrderNo(stocktakeNo)
                .orElseThrow(() -> ApiException.notFound("盘点单不存在"));
        StocktakeItem item = stocktakeItemRepository.findByStocktakeId(st.getId()).stream()
                .filter(i -> i.getChannel().getCode().equals(channelCode)
                        && i.getBatch().getBatchNo().equals(batchNo))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("盘点行不存在"));
        if (item.getDiffQty() != null && item.getDiffQty() >= 0) {
            throw ApiException.badRequest("该行无盘亏，无需发起差异调查");
        }
        return createInternal(st, item.getChannel(), item.getProduct(), item.getBatch(),
                item.getSystemQty(), item.getCountedQty(), operatorUsername);
    }

    private DiscrepancyOrder createInternal(StocktakeOrder st, Channel channel, Product product,
                                            ProductBatch batch, int systemQty, int countedQty,
                                            String operatorUsername) {
        DiscrepancyOrder existing = discrepancyRepository
                .findByBatchIdOrderByIdDesc(batch.getId()).stream()
                .filter(d -> d.getCabinet().getId().equals(st.getCabinet().getId())
                        && d.getChannel() != null && d.getChannel().getId().equals(channel.getId())
                        && d.getStatus() != DiscrepancyStatus.RESOLVED)
                .findFirst().orElse(null);
        if (existing != null) {
            // 同一在查差异单：接回（如重复盘点），不重复冻结、不重复建单
            trace(existing, "RECOUNT", 0, "STOCKTAKE_ORDER", st.getOrderNo(),
                    "盘点单 " + st.getOrderNo() + " 接回原差异单，实盘 " + countedQty, null);
            return existing;
        }

        LocalDateTime windowEnd = st.getCompletedAt() != null ? st.getCompletedAt() : LocalDateTime.now();
        LocalDateTime windowStart = resolveWindowStart(batch, st.getStartedAt());

        DiscrepancyOrder order = discrepancyRepository.save(DiscrepancyOrder.builder()
                .discrepancyNo(genNo("DC"))
                .stocktake(st).cabinet(st.getCabinet()).channel(channel)
                .product(product).batch(batch)
                .systemQty(systemQty).countedQty(countedQty)
                .shortageQty(systemQty - countedQty)
                .allocatedQty(0).pendingQty(systemQty - countedQty)
                .stockEffectedQty(0)
                .windowStart(windowStart).windowEnd(windowEnd)
                .status(DiscrepancyStatus.INVESTIGATING)
                .build());
        if (operatorUsername != null) {
            userRepository.findByUsername(operatorUsername).ifPresent(order::setInvestigator);
        }

        freezeBatch(batch, order);
        claimStocktakeRow(st, channel, batch);
        assembleEvidence(order);
        trace(order, "CREATE", order.getShortageQty(), "STOCKTAKE_ORDER", st.getOrderNo(),
                "盘点盘亏 " + order.getShortageQty() + " 件，系统 " + systemQty + " 实盘 " + countedQty
                        + "，已冻结批次结算/对账", order.getInvestigator());
        bizEventPublisher.publish("DISCREPANCY_OPENED", order.getDiscrepancyNo(),
                st.getCabinet().getCabinetNo(), batch.getBatchNo(),
                "盘点差异调查单开立，盘亏 " + order.getShortageQty() + " 件", null);
        return discrepancyRepository.save(order);
    }

    private LocalDateTime resolveWindowStart(ProductBatch batch, LocalDateTime fallback) {
        return ledgerRepository.findByBatchIdOrderByIdAsc(batch.getId()).stream()
                .filter(l -> l.getLedgerType() == LedgerType.RESTOCK_IN)
                .map(BatchLedger::getCreatedAt)
                .findFirst()
                .orElse(fallback != null ? fallback.minusDays(7) : LocalDateTime.now().minusDays(7));
    }

    /** 只冻结相关批次：其他商品/批次照常结算 */
    private void freezeBatch(ProductBatch batch, DiscrepancyOrder order) {
        if (!batch.isSettlementFrozen()) {
            batch.setSettlementFrozen(true);
            batch.setFrozenByDiscrepancyId(order.getId());
            batchRepository.save(batch);
            trace(order, "FREEZE", null, "BATCH", batch.getBatchNo(),
                    "冻结批次补货结算与供应商对账", null);
        }
    }

    /**
     * 盘亏行交由差异调查接管：标记盘点行 adjusted，避免再走旧的盘点审批
     * （/stocktakes/{no}/adjust）重复扣库存/登记报损；库存订正统一由差异拆账驱动。
     */
    private void claimStocktakeRow(StocktakeOrder st, Channel channel, ProductBatch batch) {
        stocktakeItemRepository.findByStocktakeId(st.getId()).stream()
                .filter(i -> i.getChannel().getId().equals(channel.getId())
                        && i.getBatch().getId().equals(batch.getId()))
                .findFirst()
                .ifPresent(i -> {
                    if (!i.isAdjusted()) {
                        i.setAdjusted(true);
                        stocktakeItemRepository.save(i);
                    }
                });
    }

    private void unfreezeBatch(DiscrepancyOrder order) {        ProductBatch batch = order.getBatch();
        if (batch.isSettlementFrozen()
                && order.getId().equals(batch.getFrozenByDiscrepancyId())) {
            batch.setSettlementFrozen(false);
            batch.setFrozenByDiscrepancyId(null);
            batchRepository.save(batch);
            trace(order, "UNFREEZE", null, "BATCH", batch.getBatchNo(),
                    "差异查清，解冻批次结算与对账", order.getInvestigator());
        }
    }

    /** 预装配补货前后库存、补货照片、订单、开门记录、识别、报损、温控等证据 */
    private void assembleEvidence(DiscrepancyOrder order) {
        Long batchId = order.getBatch().getId();

        // 批次台账（补货入即补货后库存，余额-变化量即补货前库存）
        for (BatchLedger l : ledgerRepository.findByBatchIdOrderByIdAsc(batchId)) {
            if (l.getLedgerType() == LedgerType.RESTOCK_IN) {
                link(order, EvidenceType.STOCK_BEFORE_RESTOCK, "BATCH_LEDGER", l.getId(), null, null,
                        "补货前该批次在柜余量 " + (l.getBalanceQty() - l.getChangeQty()) + " 件");
                link(order, EvidenceType.STOCK_AFTER_RESTOCK, "BATCH_LEDGER", l.getId(), null, null,
                        "补货上架 " + l.getChangeQty() + " 件，补货后余量 " + l.getBalanceQty() + " 件");
            } else {
                link(order, EvidenceType.LEDGER, "BATCH_LEDGER", l.getId(), null, null,
                        typeName(l.getLedgerType()) + " 变化 " + l.getChangeQty()
                                + " 记账后余量 " + l.getBalanceQty());
            }
        }

        // 补货明细 + 补货照片（含跨班次补货）
        for (RestockItem ri : restockItemRepository.findByBatchIdOrderByIdAsc(batchId)) {
            link(order, EvidenceType.RESTOCK_ORDER, "RESTOCK_ITEM", ri.getId(),
                    ri.getRestockOrder().getOrderNo(), null,
                    "补货单 " + ri.getRestockOrder().getOrderNo()
                            + " 货道 " + ri.getActualChannelCode() + " x" + ri.getQty()
                            + " 补货员 " + (ri.getRestockOrder().getReplenisher() != null
                            ? ri.getRestockOrder().getReplenisher().getDisplayName() : "?")
                            + " 完成时间 " + ri.getRestockOrder().getCompletedAt());
            if (ri.getPhotoUrl() != null) {
                link(order, EvidenceType.RESTOCK_PHOTO, "RESTOCK_ITEM", ri.getId(),
                        ri.getRestockOrder().getOrderNo(), ri.getPhotoUrl(), "上架照片");
            }
            if (ri.getRestockOrder().getPhotoUrls() != null) {
                for (String url : ri.getRestockOrder().getPhotoUrls().split(",")) {
                    if (!url.isBlank()) {
                        link(order, EvidenceType.RESTOCK_PHOTO, "RESTOCK_ORDER",
                                ri.getRestockOrder().getId(), ri.getRestockOrder().getOrderNo(),
                                url.trim(), "到柜照片");
                    }
                }
            }
        }

        // 时间窗内开门记录 + 多源识别事件
        List<DoorSession> sessions = sessionRepository
                .findByCabinetIdAndOpenedAtBetweenOrderByOpenedAtAsc(
                        order.getCabinet().getId(), order.getWindowStart(), order.getWindowEnd());
        for (DoorSession s : sessions) {
            link(order, EvidenceType.DOOR_SESSION, "DOOR_SESSION", s.getId(), s.getSessionNo(),
                    null, "开门 " + s.getOpenedAt() + " 状态 " + s.getStatus()
                            + (s.getSettleNote() != null ? " " + s.getSettleNote() : ""));
            for (RecognitionEvent e : recognitionEventRepository
                    .findBySessionIdOrderByDetectedAtAsc(s.getId())) {
                if (e.getProduct() != null && !e.getProduct().getId().equals(order.getProduct().getId())) {
                    continue;
                }
                link(order, EvidenceType.RECOGNITION_EVENT, "RECOGNITION_EVENT", e.getId(),
                        s.getSessionNo(), e.getVideoClipUrl(),
                        e.getSource() + " " + e.getAction() + " x" + e.getQty()
                                + " 置信度 " + e.getConfidence()
                                + (e.getChannelCode() != null ? " 货道 " + e.getChannelCode() : ""));
            }
        }

        // 时间窗内销售订单与明细（含已结算订单，用于漏扣研判）
        List<OrderRecord> orders = orderRepository.findByCabinetIdAndChargedAtBetweenOrderByIdAsc(
                order.getCabinet().getId(), order.getWindowStart(), order.getWindowEnd());
        for (OrderRecord o : orders) {
            List<OrderItem> items = orderItemRepository.findByOrderId(o.getId());
            boolean hit = items.stream().anyMatch(oi -> oi.getBatch() != null
                    && oi.getBatch().getId().equals(batchId));
            if (!hit) continue;
            link(order, EvidenceType.SALE_ORDER, "ORDER_RECORD", o.getId(), o.getOrderNo(), null,
                    "订单 " + o.getOrderNo() + " 状态 " + o.getStatus()
                            + " 金额 " + o.getTotalAmount() + " 扣款时间 " + o.getChargedAt());
        }

        // 时间窗内报损记录
        for (LossRecord lr : lossRepository.findByCabinetId(order.getCabinet().getId())) {
            if (lr.getBatch() == null || !lr.getBatch().getId().equals(batchId)) continue;
            if (lr.getCreatedAt().isBefore(order.getWindowStart())
                    || lr.getCreatedAt().isAfter(order.getWindowEnd())) continue;
            link(order, EvidenceType.LOSS_RECORD, "LOSS_RECORD", lr.getId(), lr.getLossNo(), null,
                    lr.getReason() + " x" + lr.getQty() + " 责任 " + lr.getLiability()
                            + (lr.isReversed() ? "（已冲销）" : ""));
        }

        // 温控曲线
        List<TempLog> temps = tempLogRepository
                .findByCabinetIdAndRecordedAtBetweenOrderByRecordedAtAsc(
                        order.getCabinet().getId(), order.getWindowStart(), order.getWindowEnd());
        for (TempLog t : temps) {
            link(order, EvidenceType.TEMP_LOG, "TEMP_LOG", t.getId(), null, null,
                    t.getRecordedAt() + " " + t.getTempC() + "℃" + (t.isAlarm() ? " 越限" : ""));
        }
    }

    private void link(DiscrepancyOrder order, EvidenceType type, String bizType, Long bizId,
                      String bizNo, String url, String note) {
        evidenceRepository.save(DiscrepancyEvidence.builder()
                .discrepancy(order).type(type).bizType(bizType).bizId(bizId)
                .bizNo(bizNo).url(url).note(note).build());
    }

    private void trace(DiscrepancyOrder order, String action, Integer qtyChange,
                       String refType, String refNo, String detail, AppUser operator) {
        traceRepository.save(DiscrepancyTrace.builder()
                .discrepancy(order).action(action).qtyChange(qtyChange)
                .refType(refType).refNo(refNo).detail(detail).operator(operator).build());
    }

    // ---------------------------------------------------------------------
    // 证据视图
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DiscrepancyEvidenceView evidence(String discrepancyNo) {
        DiscrepancyOrder order = must(discrepancyNo);
        Long batchId = order.getBatch().getId();

        List<RestockItem> restockItems = restockItemRepository.findByBatchIdOrderByIdAsc(batchId);
        List<DoorSession> sessions = sessionRepository
                .findByCabinetIdAndOpenedAtBetweenOrderByOpenedAtAsc(
                        order.getCabinet().getId(), order.getWindowStart(), order.getWindowEnd());
        List<RecognitionEvent> events = new ArrayList<>();
        for (DoorSession s : sessions) {
            events.addAll(recognitionEventRepository.findBySessionIdOrderByDetectedAtAsc(s.getId()));
        }
        List<OrderRecord> orders = orderRepository.findByCabinetIdAndChargedAtBetweenOrderByIdAsc(
                order.getCabinet().getId(), order.getWindowStart(), order.getWindowEnd());
        List<OrderItem> orderItems = new ArrayList<>();
        for (OrderRecord o : orders) {
            for (OrderItem oi : orderItemRepository.findByOrderId(o.getId())) {
                if (oi.getBatch() != null && oi.getBatch().getId().equals(batchId)) {
                    orderItems.add(oi);
                }
            }
        }
        List<LossRecord> losses = lossRepository.findByCabinetId(order.getCabinet().getId()).stream()
                .filter(l -> l.getBatch() != null && l.getBatch().getId().equals(batchId))
                .toList();
        List<TempLog> temps = tempLogRepository
                .findByCabinetIdAndRecordedAtBetweenOrderByRecordedAtAsc(
                        order.getCabinet().getId(), order.getWindowStart(), order.getWindowEnd());

        return new DiscrepancyEvidenceView(
                order,
                causeRepository.findByDiscrepancyIdOrderByOccurredAtAscIdAsc(order.getId()),
                followUpRepository.findByDiscrepancyIdOrderByIdAsc(order.getId()),
                traceRepository.findByDiscrepancyIdOrderByIdAsc(order.getId()),
                evidenceRepository.findByDiscrepancyIdOrderByIdAsc(order.getId()),
                ledgerRepository.findByBatchIdOrderByIdAsc(batchId),
                restockItems, orders, orderItems, sessions, events, losses, temps);
    }

    // ---------------------------------------------------------------------
    // 拆账归责（首次判定 / 改判）
    // ---------------------------------------------------------------------

    /**
     * 首次拆账：lines 为已能确认责任的成因条目（数量之和 ≤ 盘亏），
     * 其余数量自动挂起复核（若 sum == 盘亏则全部查清）。
     */
    @Transactional
    public DiscrepancyOrder allocate(String discrepancyNo, String username,
                                    List<AllocateLine> lines, String conclusion) {
        DiscrepancyOrder order = must(discrepancyNo);
        if (order.getStatus() == DiscrepancyStatus.RESOLVED) {
            throw ApiException.conflict("差异单已结清，如有新证据请走改判");
        }
        AppUser op = username != null ? userRepository.findByUsername(username).orElse(null) : null;
        order.setInvestigator(op);

        int confirmedQty = applyLines(order, lines, 1, op);
        int pending = order.getShortageQty() - confirmedQty;
        if (pending < 0) {
            throw ApiException.badRequest("拆账数量合计超过盘亏数量 " + order.getShortageQty());
        }
        if (pending > 0) {
            // 无法确认的部分挂起复核，不扣库存、不归责
            causeRepository.save(DiscrepancyCauseItem.builder()
                    .discrepancy(order).cause(DiscrepancyCause.PENDING)
                    .status(CauseStatus.PENDING).qty(pending)
                    .liability("UNKNOWN")
                    .occurredAt(LocalDateTime.now())
                    .evidenceNote("证据不足，挂起复核")
                    .judgeRound(1).build());
            trace(order, "PENDING", pending, null, null, "无法确认部分挂起复核", op);
        }
        order.setAllocatedQty(confirmedQty);
        order.setPendingQty(pending);
        order.setConclusion(conclusion);
        boolean resolved = pending == 0;
        order.setStatus(resolved ? DiscrepancyStatus.RESOLVED : DiscrepancyStatus.PENDING_REVIEW);
        if (resolved) {
            order.setResolvedAt(LocalDateTime.now());
            unfreezeBatch(order);
        }
        trace(order, "ALLOCATE", confirmedQty, null, null,
                "按时间与证据拆账：确认 " + confirmedQty + " 件，挂起 " + pending + " 件", op);
        bizEventPublisher.publish(
                resolved ? "DISCREPANCY_RESOLVED" : "DISCREPANCY_PENDING_REVIEW",
                order.getDiscrepancyNo(), order.getCabinet().getCabinetNo(),
                order.getBatch().getBatchNo(),
                (resolved ? "差异全部查清 " : "差异部分挂起复核 ") + confirmedQty + "/"
                        + order.getShortageQty(), null);
        return discrepancyRepository.save(order);
    }

    /**
     * 改判：补传照片/重复盘点/跨班次补货/已结算订单接回后重新判定。
     * 冲销旧拆账条目及其库存/报损/评分影响，按新一轮条目重建，全程留痕。
     */
    @Transactional
    public DiscrepancyOrder rejudge(String discrepancyNo, String username,
                                    List<AllocateLine> lines, String conclusion) {
        DiscrepancyOrder order = must(discrepancyNo);
        AppUser op = username != null ? userRepository.findByUsername(username).orElse(null) : null;
        order.setInvestigator(op);
        int round = causeRepository.findByDiscrepancyIdOrderByOccurredAtAscIdAsc(order.getId()).stream()
                .mapToInt(DiscrepancyCauseItem::getJudgeRound).max().orElse(0) + 1;

        // 1) 冲销旧条目的库存/报损/补货员评分影响
        reverseExistingEffects(order, op);

        // 2) 旧条目全部置 REVERSED（保留留痕）
        for (DiscrepancyCauseItem c : causeRepository
                .findByDiscrepancyIdOrderByOccurredAtAscIdAsc(order.getId())) {
            if (c.getStatus() == CauseStatus.REVERSED) continue;
            c.setStatus(CauseStatus.REVERSED);
            causeRepository.save(c);
        }

        // 3) 按新一轮重建
        int confirmedQty = applyLines(order, lines, round, op);
        int pending = order.getShortageQty() - confirmedQty;
        if (pending < 0) {
            throw ApiException.badRequest("拆账数量合计超过盘亏数量 " + order.getShortageQty());
        }
        if (pending > 0) {
            causeRepository.save(DiscrepancyCauseItem.builder()
                    .discrepancy(order).cause(DiscrepancyCause.PENDING)
                    .status(CauseStatus.PENDING).qty(pending)
                    .liability("UNKNOWN").occurredAt(LocalDateTime.now())
                    .evidenceNote("改判后仍无法确认，继续挂起复核").judgeRound(round).build());
        }
        order.setAllocatedQty(confirmedQty);
        order.setPendingQty(pending);
        order.setStockEffectedQty(0);
        order.setConclusion(conclusion);
        boolean resolved = pending == 0;
        order.setStatus(resolved ? DiscrepancyStatus.RE_JUDGED : DiscrepancyStatus.PENDING_REVIEW);
        if (resolved) {
            order.setResolvedAt(LocalDateTime.now());
            unfreezeBatch(order);
        } else {
            freezeBatch(order.getBatch(), order);
        }
        trace(order, "RE_JUDGE", null, null, null,
                "第 " + round + " 轮改判：确认 " + confirmedQty + " 件，挂起 " + pending
                        + " 件。" + (conclusion != null ? conclusion : ""), op);
        bizEventPublisher.publish("DISCREPANCY_REJUDGED", order.getDiscrepancyNo(),
                order.getCabinet().getCabinetNo(), order.getBatch().getBatchNo(),
                "差异改判 第" + round + "轮", null);
        return discrepancyRepository.save(order);
    }

    /** 应用一组拆账条目：扣库存、记账、登记报损、生下游流程、扣补货员评分。 */
    private int applyLines(DiscrepancyOrder order, List<AllocateLine> lines, int round, AppUser op) {
        int confirmedQty = 0;
        LocalDateTime baseTime = LocalDateTime.now();
        int index = 0;
        for (AllocateLine line : lines) {
            DiscrepancyCause cause;
            try {
                cause = DiscrepancyCause.valueOf(line.cause());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("未知成因: " + line.cause());
            }
            if (cause == DiscrepancyCause.PENDING) {
                throw ApiException.badRequest("挂起数量由系统自动计算，无需显式传入 PENDING");
            }
            int qty = line.qty() == null ? 0 : line.qty();
            if (qty <= 0) {
                throw ApiException.badRequest("拆账数量必须大于 0");
            }
            if (confirmedQty + qty > order.getShortageQty()) {
                throw ApiException.badRequest("拆账数量合计超过盘亏 " + order.getShortageQty());
            }
            String liability = resolveLiability(cause, line.liability());
            LocalDateTime occurredAt = line.occurredAt() != null ? line.occurredAt()
                    : baseTime.plusSeconds(index++);

            DiscrepancyCauseItem item = DiscrepancyCauseItem.builder()
                    .discrepancy(order).cause(cause).status(CauseStatus.CONFIRMED)
                    .qty(qty).liability(liability).occurredAt(occurredAt)
                    .refType(line.refType()).refNo(line.refNo())
                    .evidenceNote(line.evidenceNote()).judgeRound(round)
                    .costAmount(cost(order.getBatch(), qty))
                    .build();

            applyStockAndLoss(order, item, line);
            causeRepository.save(item);
            createFollowUp(order, item, line, op);
            penalizeReplenisherIfNeeded(order, item, op);
            confirmedQty += qty;
        }
        return confirmedQty;
    }

    /** 按成影响库存与报损（温控/漏单/漏记扣减库存；销售漏扣/补货少放为库存订正）。 */
    private void applyStockAndLoss(DiscrepancyOrder order, DiscrepancyCauseItem item,
                                   AllocateLine line) {
        int qty = item.getQty();
        ProductBatch batch = order.getBatch();
        BigDecimal amount = cost(batch, qty);

        switch (item.getCause()) {
            case TEMP_ABUSE -> {
                // 食品安全：先行安全处置（隔离/报废），不等普通追责
                deductStock(order, qty);
                ledgerService.record(batch, order.getCabinet(), LedgerType.LOSS_OUT, -qty, amount,
                        "DISCREPANCY", order.getId(),
                        "差异单温控异常损耗处置 " + order.getDiscrepancyNo());
                LossRecord loss = saveLoss(order, LossReason.TEMP_ABUSE, qty, amount,
                        "OPERATOR", "温控异常损耗，安全处置");
                item.setLossId(loss.getId());
                order.setStockEffectedQty(order.getStockEffectedQty() + qty);
            }
            case RECOGNITION_MISS -> {
                // 用户拿走未出单：补扣库存，平台承担并回流算法样本；不计供应商责任
                deductStock(order, qty);
                ledgerService.record(batch, order.getCabinet(), LedgerType.LOSS_OUT, -qty, amount,
                        "DISCREPANCY", order.getId(),
                        "差异单识别漏单补扣 " + order.getDiscrepancyNo());
                LossRecord loss = saveLoss(order, LossReason.RECOGNITION_MISS, qty, amount,
                        "PLATFORM", "识别漏单，样本回流算法");
                item.setLossId(loss.getId());
                order.setStockEffectedQty(order.getStockEffectedQty() + qty);
            }
            case LOSS_MISSED -> {
                // 实际损耗未登记：报废出库
                deductStock(order, qty);
                ledgerService.record(batch, order.getCabinet(), LedgerType.LOSS_OUT, -qty, amount,
                        "DISCREPANCY", order.getId(),
                        "差异单补记报损 " + order.getDiscrepancyNo());
                LossRecord loss = saveLoss(order, LossReason.LOSS_MISSED, qty, amount,
                        item.getLiability(), line.evidenceNote() != null ? line.evidenceNote() : "报损漏记补登");
                item.setLossId(loss.getId());
                order.setStockEffectedQty(order.getStockEffectedQty() + qty);
            }
            case SALE_NOT_DEDUCTED -> {
                // 销售已发生但库存漏扣：订正库存，不登记报损（向用户/平台补扣款走另一流程）
                deductStock(order, qty);
                ledgerService.record(batch, order.getCabinet(), LedgerType.ADJUST_MINUS, -qty, amount,
                        "DISCREPANCY", order.getId(),
                        "差异单销售漏扣订正 " + order.getDiscrepancyNo());
                order.setStockEffectedQty(order.getStockEffectedQty() + qty);
            }
            case RESTOCK_SHORT -> {
                // 补货时少放：系统虚高，订正库存；补货员结算/评分负责，不记供应商报损
                deductStock(order, qty);
                ledgerService.record(batch, order.getCabinet(), LedgerType.ADJUST_MINUS, -qty, amount,
                        "DISCREPANCY", order.getId(),
                        "差异单补货少放订正 " + order.getDiscrepancyNo());
                order.setStockEffectedQty(order.getStockEffectedQty() + qty);
            }
            default -> { }
        }
    }

    private void deductStock(DiscrepancyOrder order, int qty) {
        if (order.getChannel() == null) {
            throw ApiException.conflict("差异单缺少货道信息，无法订正库存");
        }
        ChannelStock stock = stockRepository
                .findByChannelIdAndBatchId(order.getChannel().getId(), order.getBatch().getId())
                .orElseThrow(() -> ApiException.conflict("货道批次库存记录缺失"));
        int removable = stock.getQtyOnHand();
        int take = Math.min(qty, removable);
        if (take <= 0) {
            throw ApiException.conflict("货道库存已为 0，无法继续订正（可能重复拆账）");
        }
        stock.setQtyOnHand(removable - take);
        stock.setQtyQuarantined(Math.max(0, stock.getQtyQuarantined() - take));
        stockRepository.save(stock);
        if (take < qty) {
            log.warn("差异单 {} 库存不足，按实际可扣 {} 件（申请 {} 件）",
                    order.getDiscrepancyNo(), take, qty);
        }
    }

    private LossRecord saveLoss(DiscrepancyOrder order, LossReason reason, int qty,
                                BigDecimal amount, String liability, String remark) {
        return lossRepository.save(LossRecord.builder()
                .lossNo(genNo("LS"))
                .cabinet(order.getCabinet()).product(order.getProduct()).batch(order.getBatch())
                .reason(reason).qty(qty).costAmount(amount)
                .liability(liability).bizType("DISCREPANCY").bizId(order.getId())
                .remark(remark + " " + order.getDiscrepancyNo()).build());
    }

    /** 按成因生对应下游流程 */
    private void createFollowUp(DiscrepancyOrder order, DiscrepancyCauseItem item,
                                AllocateLine line, AppUser op) {
        FollowUpType type = switch (item.getCause()) {
            case RESTOCK_SHORT -> FollowUpType.REPLENISHER_SETTLEMENT;
            case RECOGNITION_MISS -> FollowUpType.ALGORITHM_SAMPLE;
            case TEMP_ABUSE -> FollowUpType.FOOD_SAFETY_REVIEW;
            case LOSS_MISSED -> "SUPPLIER".equals(item.getLiability())
                    ? FollowUpType.SUPPLIER_BILL : FollowUpType.REPLENISHER_SETTLEMENT;
            case SALE_NOT_DEDUCTED -> FollowUpType.NONE;
            default -> FollowUpType.NONE;
        };
        if (type == FollowUpType.NONE) return;

        FollowUpStatus status = FollowUpStatus.PENDING;
        String detail;
        String refNo = null;
        switch (type) {
            case FOOD_SAFETY_REVIEW -> {
                // 温控异常商品先行安全处置，食品安全复核即时完成
                status = FollowUpStatus.DONE;
                refNo = "FS" + genSeq();
                detail = "温度异常商品已先行隔离/报废 " + item.getQty() + " 件，安全处置单 " + refNo;
                trace(order, "FOOD_SAFETY", item.getQty(), "FOOD_SAFETY", refNo, detail, op);
            }
            case REPLENISHER_SETTLEMENT -> detail = "计入补货员结算并扣评分 "
                    + item.getQty() + " 件，成本 " + item.getCostAmount();
            case SUPPLIER_BILL -> detail = "从供应商账单扣减 " + item.getQty()
                    + " 件，金额 " + item.getCostAmount();
            case ALGORITHM_SAMPLE -> {
                detail = "漏单样本回流识别算法（关联开门/视频证据）" + item.getQty() + " 件";
                refNo = line.refNo();
            }
            default -> detail = item.getCause() + " x" + item.getQty();
        }
        followUpRepository.save(DiscrepancyFollowUp.builder()
                .discrepancy(order).causeItem(item).type(type).status(status)
                .detail(detail).refNo(refNo).build());
        if (status == FollowUpStatus.PENDING) {
            trace(order, "FOLLOW_UP", item.getQty(), type.name(), refNo, detail, op);
        }
    }

    /** 补货员责任：累计责任数量并扣评分 */
    private void penalizeReplenisherIfNeeded(DiscrepancyOrder order, DiscrepancyCauseItem item,
                                             AppUser op) {
        boolean replenisherLiable = item.getCause() == DiscrepancyCause.RESTOCK_SHORT
                || ("REPLENISHER".equals(item.getLiability())
                        && item.getCause() == DiscrepancyCause.LOSS_MISSED);
        if (!replenisherLiable) return;
        AppUser replenisher = findRestockReplenisher(order);
        if (replenisher == null) return;
        replenisher.setLiabilityQty(replenisher.getLiabilityQty() + item.getQty());
        replenisher.setScore(Math.max(0, replenisher.getScore()
                - item.getQty() * SCORE_POINTS_PER_UNIT));
        userRepository.save(replenisher);
    }

    private AppUser findRestockReplenisher(DiscrepancyOrder order) {
        return restockItemRepository.findByBatchIdOrderByIdAsc(order.getBatch().getId()).stream()
                .filter(ri -> ri.getRestockOrder().getCabinet() != null
                        && ri.getRestockOrder().getCabinet().getId().equals(order.getCabinet().getId()))
                .map(ri -> ri.getRestockOrder().getReplenisher())
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** 改判时冲销上一轮：回补库存、红冲报损、回退补货员评分/责任量 */
    private void reverseExistingEffects(DiscrepancyOrder order, AppUser op) {
        List<DiscrepancyCauseItem> olds = causeRepository
                .findByDiscrepancyIdOrderByOccurredAtAscIdAsc(order.getId());
        for (DiscrepancyCauseItem c : olds) {
            if (c.getStatus() == CauseStatus.REVERSED) continue;
            int qty = c.getQty();
            ProductBatch batch = order.getBatch();
            boolean movedStock = c.getCause() != DiscrepancyCause.PENDING;

            if (movedStock) {
                // 回补货道批次库存
                if (order.getChannel() != null) {
                    ChannelStock stock = stockRepository
                            .findByChannelIdAndBatchId(order.getChannel().getId(), batch.getId())
                            .orElse(null);
                    if (stock != null) {
                        stock.setQtyOnHand(stock.getQtyOnHand() + qty);
                        stockRepository.save(stock);
                    }
                }
                BigDecimal amount = c.getCostAmount() != null ? c.getCostAmount()
                        : cost(batch, qty);
                ledgerService.record(batch, order.getCabinet(), LedgerType.ADJUST_PLUS, qty, amount,
                        "DISCREPANCY", order.getId(),
                        "改判冲销回补 " + c.getCause() + " " + order.getDiscrepancyNo());
            }
            // 红冲报损（不再计入供应商结算）
            if (c.getLossId() != null) {
                lossRepository.findById(c.getLossId()).ifPresent(lr -> {
                    lr.setReversed(true);
                    lr.setReversedByDiscrepancyNo(order.getDiscrepancyNo());
                    lossRepository.save(lr);
                });
            }
            // 回退补货员评分/责任量
            boolean replenisherLiable = c.getCause() == DiscrepancyCause.RESTOCK_SHORT
                    || ("REPLENISHER".equals(c.getLiability())
                            && c.getCause() == DiscrepancyCause.LOSS_MISSED);
            if (replenisherLiable) {
                AppUser replenisher = findRestockReplenisher(order);
                if (replenisher != null) {
                    replenisher.setLiabilityQty(Math.max(0,
                            replenisher.getLiabilityQty() - qty));
                    replenisher.setScore(Math.min(100,
                            replenisher.getScore() + qty * SCORE_POINTS_PER_UNIT));
                    userRepository.save(replenisher);
                }
            }
            // 下游流程标记为作废（由新一轮重新生成）
            for (DiscrepancyFollowUp fu : followUpRepository
                    .findByDiscrepancyIdOrderByIdAsc(order.getId())) {
                if (fu.getCauseItem() != null && fu.getCauseItem().getId().equals(c.getId())
                        && fu.getStatus() == FollowUpStatus.PENDING) {
                    fu.setStatus(FollowUpStatus.DONE);
                    fu.setDetail("[改判作废] " + fu.getDetail());
                    followUpRepository.save(fu);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 补传照片 / 重复盘点 / 跨班次补货 / 已结算订单 接回
    // ---------------------------------------------------------------------

    /** 事后补传照片，接回原差异单（不改数量，供后续改判） */
    @Transactional
    public DiscrepancyOrder attachPhoto(String discrepancyNo, String username,
                                       String photoUrl, String note) {
        DiscrepancyOrder order = must(discrepancyNo);
        AppUser op = username != null ? userRepository.findByUsername(username).orElse(null) : null;
        if (photoUrl == null || photoUrl.isBlank()) {
            throw ApiException.badRequest("照片 URL 不能为空");
        }
        link(order, EvidenceType.UPLOADED_PHOTO, "UPLOAD", null, null, photoUrl, note);
        trace(order, "PHOTO_UPLOAD", null, "UPLOADED_PHOTO", photoUrl,
                "补传照片" + (note != null ? "：" + note : ""), op);
        return order;
    }

    /**
     * 重复盘点接回原差异单：以新实盘数量更新差额。
     * 若数量发生变化，原差异单回到调查中（维持冻结），需走改判重新拆账。
     */
    @Transactional
    public DiscrepancyOrder attachRecount(String discrepancyNo, String username,
                                         int newCountedQty, String note) {
        DiscrepancyOrder order = must(discrepancyNo);
        AppUser op = username != null ? userRepository.findByUsername(username).orElse(null) : null;
        if (newCountedQty < 0 || newCountedQty > order.getSystemQty()) {
            throw ApiException.badRequest("重复盘点数量需在 0 与系统库存 " + order.getSystemQty() + " 之间");
        }
        int oldCounted = order.getCountedQty();
        int newShortage = order.getSystemQty() - newCountedQty;
        int qtyChange = newCountedQty - oldCounted; // 实盘增加=差额减小

        // 建一张接回用盘点单（快照当前库存 + 新实盘）
        StocktakeOrder st = stocktakeRepository.save(StocktakeOrder.builder()
                .orderNo(genNo("ST")).cabinet(order.getCabinet()).operator(op)
                .startedAt(LocalDateTime.now()).completedAt(LocalDateTime.now())
                .status(newShortage > 0 ? StocktakeStatus.PENDING_ADJUST : StocktakeStatus.COUNTED)
                .totalDiff(Math.abs(newShortage))
                .remark("差异单 " + order.getDiscrepancyNo() + " 重复盘点接回").build());
        stocktakeItemRepository.save(StocktakeItem.builder()
                .stocktake(st).channel(order.getChannel()).product(order.getProduct())
                .batch(order.getBatch()).systemQty(order.getSystemQty())
                .countedQty(newCountedQty).diffQty(newCountedQty - order.getSystemQty())
                .adjusted(false).build());

        link(order, EvidenceType.STOCKTAKE_ORDER, "STOCKTAKE_ORDER", st.getId(), st.getOrderNo(),
                null, "重复盘点 实盘 " + newCountedQty + (note != null ? " " + note : ""));
        order.setCountedQty(newCountedQty);
        order.setShortageQty(newShortage);
        if (qtyChange != 0) {
            // 数量基础变了：原拆账作废、回退到调查中，等待改判；冻结维持
            reverseExistingEffects(order, op);
            for (DiscrepancyCauseItem c : causeRepository
                    .findByDiscrepancyIdOrderByOccurredAtAscIdAsc(order.getId())) {
                if (c.getStatus() != CauseStatus.REVERSED) {
                    c.setStatus(CauseStatus.REVERSED);
                    causeRepository.save(c);
                }
            }
            order.setAllocatedQty(0);
            order.setPendingQty(newShortage);
            order.setStockEffectedQty(0);
            order.setStatus(DiscrepancyStatus.INVESTIGATING);
            order.setResolvedAt(null);
            freezeBatch(order.getBatch(), order);
        }
        trace(order, "RECOUNT", qtyChange, "STOCKTAKE_ORDER", st.getOrderNo(),
                "重复盘点实盘由 " + oldCounted + " 变为 " + newCountedQty
                        + (qtyChange != 0 ? "，原拆账回退，待改判" : "，数量无变化"), op);
        return discrepancyRepository.save(order);
    }

    /** 跨班次补货接回：把另一班次的补货单纳入证据 */
    @Transactional
    public DiscrepancyOrder attachCrossShiftRestock(String discrepancyNo, String username,
                                                    String restockOrderNo, String note) {
        DiscrepancyOrder order = must(discrepancyNo);
        AppUser op = username != null ? userRepository.findByUsername(username).orElse(null) : null;
        RestockOrder rs = restockOrderRepository.findByOrderNo(restockOrderNo)
                .orElseThrow(() -> ApiException.notFound("补货单不存在: " + restockOrderNo));
        if (!rs.getCabinet().getId().equals(order.getCabinet().getId())) {
            throw ApiException.badRequest("补货单柜机与差异单不一致");
        }
        boolean batchHit = restockItemRepository.findByRestockOrderId(rs.getId()).stream()
                .anyMatch(ri -> ri.getBatch().getId().equals(order.getBatch().getId()));
        if (!batchHit) {
            throw ApiException.badRequest("该补货单不含差异批次");
        }
        link(order, EvidenceType.RESTOCK_ORDER, "RESTOCK_ORDER", rs.getId(), rs.getOrderNo(),
                rs.getPhotoUrls(), "跨班次补货接回：班次补货员 "
                + (rs.getReplenisher() != null ? rs.getReplenisher().getDisplayName() : "?")
                + "，完成 " + rs.getCompletedAt() + (note != null ? " " + note : ""));
        trace(order, "CROSS_SHIFT_RESTOCK", null, "RESTOCK_ORDER", rs.getOrderNo(),
                "跨班次补货单接回，完成时间 " + rs.getCompletedAt(), op);
        return order;
    }

    /** 已结算订单接回：销售已扣款但差异调查需要核对是否漏扣/错扣 */
    @Transactional
    public DiscrepancyOrder attachSettledOrder(String discrepancyNo, String username,
                                               String orderNo, String note) {
        DiscrepancyOrder order = must(discrepancyNo);
        AppUser op = username != null ? userRepository.findByUsername(username).orElse(null) : null;
        OrderRecord o = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> ApiException.notFound("订单不存在: " + orderNo));
        if (!o.getCabinet().getId().equals(order.getCabinet().getId())) {
            throw ApiException.badRequest("订单柜机与差异单不一致");
        }
        link(order, EvidenceType.SETTLED_ORDER, "ORDER_RECORD", o.getId(), o.getOrderNo(), null,
                "已结算订单接回核对，状态 " + o.getStatus() + " 金额 " + o.getTotalAmount()
                        + (note != null ? " " + note : ""));
        if (o.getSession() != null) {
            link(order, EvidenceType.DOOR_SESSION, "DOOR_SESSION", o.getSession().getId(),
                    o.getSession().getSessionNo(), null, "已结算订单开门记录");
        }
        trace(order, "SETTLED_ORDER_LINK", null, "ORDER_RECORD", o.getOrderNo(),
                "已结算订单接回差异单核对", op);
        return order;
    }

    /** 标记某类下游流程已处理（如供应商账单已扣、算法样本已入库） */
    @Transactional
    public DiscrepancyFollowUp completeFollowUp(String discrepancyNo, String username,
                                                FollowUpType type, String refNo, String detail) {
        DiscrepancyOrder order = must(discrepancyNo);
        AppUser op = username != null ? userRepository.findByUsername(username).orElse(null) : null;
        DiscrepancyFollowUp fu = followUpRepository.findByDiscrepancyIdOrderByIdAsc(order.getId())
                .stream()
                .filter(f -> f.getType() == type && f.getStatus() == FollowUpStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("没有待处理的该类下游流程: " + type));
        fu.setStatus(FollowUpStatus.DONE);
        if (refNo != null) fu.setRefNo(refNo);
        if (detail != null) fu.setDetail(detail);
        followUpRepository.save(fu);
        trace(order, "FOLLOW_UP", null, type.name(), refNo,
                "下游流程办结 " + type + (refNo != null ? " " + refNo : ""), op);
        return fu;
    }

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DiscrepancyOrder> list(String cabinetNo, List<DiscrepancyStatus> statuses) {
        List<DiscrepancyOrder> result;
        if (cabinetNo != null) {
            Cabinet c = cabinetRepository.findByCabinetNo(cabinetNo)
                    .orElseThrow(() -> ApiException.notFound("柜机不存在"));
            result = new ArrayList<>(discrepancyRepository.findByCabinetIdOrderByIdDesc(c.getId()));
        } else if (statuses != null && !statuses.isEmpty()) {
            result = new ArrayList<>(discrepancyRepository.findByStatusInOrderByIdDesc(statuses));
        } else {
            result = new ArrayList<>(discrepancyRepository.findAll());
        }
        if (cabinetNo != null && statuses != null && !statuses.isEmpty()) {
            result.removeIf(d -> !statuses.contains(d.getStatus()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public DiscrepancyOrder get(String discrepancyNo) {
        return must(discrepancyNo);
    }

    @Transactional(readOnly = true)
    public List<DiscrepancyCauseItem> causes(String discrepancyNo) {
        DiscrepancyOrder order = must(discrepancyNo);
        return causeRepository.findByDiscrepancyIdOrderByOccurredAtAscIdAsc(order.getId());
    }

    @Transactional(readOnly = true)
    public List<DiscrepancyFollowUp> followUps(String discrepancyNo) {
        DiscrepancyOrder order = must(discrepancyNo);
        return followUpRepository.findByDiscrepancyIdOrderByIdAsc(order.getId());
    }

    @Transactional(readOnly = true)
    public List<DiscrepancyTrace> traces(String discrepancyNo) {
        DiscrepancyOrder order = must(discrepancyNo);
        return traceRepository.findByDiscrepancyIdOrderByIdAsc(order.getId());
    }

    // ---------------------------------------------------------------------

    private DiscrepancyOrder must(String no) {
        return discrepancyRepository.findByDiscrepancyNo(no)
                .orElseThrow(() -> ApiException.notFound("差异单不存在: " + no));
    }

    private String resolveLiability(DiscrepancyCause cause, String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit.toUpperCase();
        return switch (cause) {
            case RESTOCK_SHORT -> "REPLENISHER";
            case RECOGNITION_MISS, SALE_NOT_DEDUCTED -> "PLATFORM";
            case TEMP_ABUSE -> "OPERATOR";
            case LOSS_MISSED -> "OPERATOR";
            default -> "UNKNOWN";
        };
    }

    private BigDecimal cost(ProductBatch batch, int qty) {
        BigDecimal unit = batch.getCostPrice() != null ? batch.getCostPrice() : BigDecimal.ZERO;
        return unit.multiply(BigDecimal.valueOf(qty));
    }

    private String typeName(LedgerType t) {
        return switch (t) {
            case RESTOCK_IN -> "补货入";
            case SALE_OUT -> "售卖出";
            case REMOVAL_OUT -> "下架出";
            case LOSS_OUT -> "报损出";
            case ADJUST_PLUS -> "盘盈/回补";
            case ADJUST_MINUS -> "盘亏/订正";
            case REFUND_RETURN -> "退款回库";
            case SETTLE -> "结算";
        };
    }

    private String genNo(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }

    private String genSeq() {
        return LocalDate.now().toString().replace("-", "")
                + String.format("%06d", System.nanoTime() % 1_000_000);
    }

    /**
     * 拆账入参。
     * cause: SALE_NOT_DEDUCTED/RESTOCK_SHORT/LOSS_MISSED/RECOGNITION_MISS/TEMP_ABUSE
     * liability 可覆盖默认责任（SUPPLIER/REPLENISHER/OPERATOR/PLATFORM）。
     */
    public record AllocateLine(String cause, Integer qty, String liability,
                               LocalDateTime occurredAt, String refType, String refNo,
                               String evidenceNote) {}
}
