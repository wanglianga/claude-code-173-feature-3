package com.cabinet.service;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.exception.ApiException;
import com.cabinet.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 财务结算服务：按供应商 + 周期，依据批次台账汇总
 * 真实售卖（SALE_OUT - REFUND_RETURN）与报损（LOSS_OUT/LossRecord 中 SUPPLIER 责任），
 * 逐批次生成结算明细；应付款 = 售卖成本 - 供应商责任报损 + 差异核查对账调整。
 *
 * 盘点差异核查期间，只冻结"相关批次"：被差异单冻结的批次整批不进入本期结算
 * （售卖/报损/对账调整一并挂起），其他商品/批次照常结算；
 * 差异改判作废的报损（voided）不计入；差异产生的供应商对账调整在结算确认时核销。
 * 同一供应商同一周期不允许重复确认。
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementBatchRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final SupplierRepository supplierRepository;
    private final BatchLedgerRepository ledgerRepository;
    private final LossRecordRepository lossRepository;
    private final ProductBatchRepository batchRepository;
    private final OrderItemRepository orderItemRepository;
    private final DiscrepancyOrderRepository discrepancyRepository;
    private final ReconAdjustmentRepository reconRepository;
    private final BizEventPublisher bizEventPublisher;

    /** 生成某供应商周期结算单草稿（按批次聚合真实售卖成本、报损与对账调整） */
    @Transactional
    public SettlementBatch generate(Long supplierId, LocalDate start, LocalDate end) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> ApiException.notFound("供应商不存在"));
        boolean exists = settlementRepository.existsBySupplierIdAndStatusInAndPeriodStartAndPeriodEnd(
                supplierId, List.of(SettlementStatus.DRAFT, SettlementStatus.CONFIRMED,
                        SettlementStatus.PAID), start, end);
        if (exists) {
            throw ApiException.conflict("该周期已存在结算单");
        }

        // 该供应商所有批次
        List<ProductBatch> batches = batchRepository.findAll().stream()
                .filter(b -> b.getSupplier() != null && b.getSupplier().getId().equals(supplierId))
                .toList();

        SettlementBatch sb = SettlementBatch.builder()
                .settlementNo(genNo("STL"))
                .supplier(supplier)
                .periodStart(start).periodEnd(end)
                .status(SettlementStatus.DRAFT)
                .build();
        settlementRepository.save(sb);

        int totalSold = 0, totalLoss = 0, heldBatches = 0;
        BigDecimal soldCostSum = BigDecimal.ZERO, lossCostSum = BigDecimal.ZERO, reconSum = BigDecimal.ZERO;
        List<String> heldBatchNos = new ArrayList<>();
        Set<Long> settledBatchIds = new LinkedHashSet<>();

        for (ProductBatch batch : batches) {
            // 差异未查清：整批冻结，售卖成本/报损/对账均不进入本期结算
            if (!discrepancyRepository.findByBatchIdAndSettlementFrozenTrue(batch.getId()).isEmpty()) {
                heldBatches++;
                heldBatchNos.add(batch.getBatchNo());
                continue;
            }

            // 真实售卖：周期内订单明细（按批次），扣除整单退款数量
            int soldQty = 0;
            BigDecimal soldCost = BigDecimal.ZERO;
            for (OrderItem oi : orderItemRepository.findByBatchId(batch.getId())) {
                LocalDateTime chargedAt = oi.getOrder().getChargedAt();
                if (chargedAt == null || chargedAt.toLocalDate().isBefore(start)
                        || chargedAt.toLocalDate().isAfter(end)) {
                    continue;
                }
                int netSold = oi.getQty() - oi.getRefundedQty();
                if (netSold <= 0) continue;
                soldQty += netSold;
                BigDecimal unitCost = batch.getCostPrice() != null
                        ? batch.getCostPrice() : BigDecimal.ZERO;
                soldCost = soldCost.add(unitCost.multiply(BigDecimal.valueOf(netSold)));
            }

            // 供应商责任报损（改判作废的不计入；差异核查定责的供应商报损统一走对账调整，
            // 不在此重复扣减）
            int lossQty = 0;
            BigDecimal lossCost = BigDecimal.ZERO;
            for (LossRecord lr : lossRepository.findByBatchIdOrderByIdAsc(batch.getId())) {
                LocalDateTime t = lr.getCreatedAt();
                if (t.toLocalDate().isBefore(start) || t.toLocalDate().isAfter(end)) continue;
                if (lr.isVoided()) continue;
                if ("SUPPLIER".equals(lr.getLiability()) && !"DISCREPANCY".equals(lr.getBizType())) {
                    lossQty += lr.getQty();
                    lossCost = lossCost.add(lr.getCostAmount() != null
                            ? lr.getCostAmount() : BigDecimal.ZERO);
                }
            }

            // 差异核查产生的未核销对账调整（冻结批次已在上方跳过，此处一定可核销）
            BigDecimal reconSigned = BigDecimal.ZERO;
            for (ReconAdjustment adj : reconRepository
                    .findBySupplierIdAndAppliedFalseOrderByCreatedAtAsc(supplierId)) {
                if (adj.isVoided() || adj.getBatch() == null
                        || !adj.getBatch().getId().equals(batch.getId())) {
                    continue;
                }
                BigDecimal amt = adj.getAmount() != null ? adj.getAmount() : BigDecimal.ZERO;
                reconSigned = reconSigned.add(
                        adj.getDirection() == ReconDirection.ADD ? amt : amt.negate());
            }

            if (soldQty == 0 && lossQty == 0 && reconSigned.signum() == 0) continue;

            BigDecimal payable = soldCost.subtract(lossCost).add(reconSigned);
            settlementItemRepository.save(SettlementItem.builder()
                    .settlement(sb).batch(batch).product(batch.getProduct())
                    .soldQty(soldQty).lossQty(lossQty)
                    .soldCost(soldCost).lossCost(lossCost)
                    .reconAmount(reconSigned)
                    .payableAmount(payable)
                    .build());
            settledBatchIds.add(batch.getId());
            totalSold += soldQty;
            totalLoss += lossQty;
            soldCostSum = soldCostSum.add(soldCost);
            lossCostSum = lossCostSum.add(lossCost);
            reconSum = reconSum.add(reconSigned);

            ledgerRepository.save(BatchLedger.builder()
                    .batch(batch).cabinet(null)
                    .ledgerType(LedgerType.SETTLE)
                    .changeQty(0)
                    .balanceQty(0)
                    .amount(payable)
                    .bizType("SETTLEMENT").bizId(sb.getId())
                    .remark("周期结算 " + start + "~" + end)
                    .build());
        }

        sb.setSoldQty(totalSold);
        sb.setLossQty(totalLoss);
        sb.setSoldCost(soldCostSum);
        sb.setLossCost(lossCostSum);
        sb.setPayableAmount(soldCostSum.subtract(lossCostSum).add(reconSum));
        sb.setHeldBatchCount(heldBatches);
        sb.setHeldBatchNos(String.join(",", heldBatchNos));
        sb.setRemark(heldBatches > 0
                ? "差异核查冻结 " + heldBatches + " 个批次，未纳入本期结算: " + String.join(",", heldBatchNos)
                : null);
        return settlementRepository.save(sb);
    }

    @Transactional
    public SettlementBatch confirm(String settlementNo) {
        SettlementBatch sb = must(settlementNo);
        if (sb.getStatus() != SettlementStatus.DRAFT) {
            throw ApiException.conflict("仅草稿状态可确认");
        }
        // 确认前再校验：明细批次在草稿期间被差异冻结则拒绝确认，需重新生成
        List<SettlementItem> items = settlementItemRepository.findBySettlementId(sb.getId());
        for (SettlementItem si : items) {
            if (!discrepancyRepository
                    .findByBatchIdAndSettlementFrozenTrue(si.getBatch().getId()).isEmpty()) {
                throw ApiException.conflict("批次 " + si.getBatch().getBatchNo()
                        + " 已被差异核查冻结，本结算单需作废后重新生成");
            }
        }
        // 核销明细批次上的未核销对账调整
        LocalDateTime now = LocalDateTime.now();
        for (SettlementItem si : items) {
            for (ReconAdjustment adj : reconRepository
                    .findBySupplierIdAndAppliedFalseOrderByCreatedAtAsc(sb.getSupplier().getId())) {
                if (adj.isVoided() || adj.getBatch() == null
                        || !adj.getBatch().getId().equals(si.getBatch().getId())) {
                    continue;
                }
                adj.setApplied(true);
                adj.setAppliedInSettlementNo(sb.getSettlementNo());
                adj.setAppliedAt(now);
                reconRepository.save(adj);
            }
        }

        sb.setStatus(SettlementStatus.CONFIRMED);
        sb.setConfirmedAt(now);
        settlementRepository.save(sb);
        bizEventPublisher.publish("SETTLEMENT_CONFIRMED", sb.getSettlementNo(),
                null, null,
                "供应商 " + sb.getSupplier().getCode() + " 结算确认 应付 " + sb.getPayableAmount()
                        + (sb.getHeldBatchCount() > 0 ? "，冻结批次 " + sb.getHeldBatchCount() : ""),
                sb.getPayableAmount());
        return sb;
    }

    @Transactional
    public SettlementBatch markPaid(String settlementNo) {
        SettlementBatch sb = must(settlementNo);
        if (sb.getStatus() != SettlementStatus.CONFIRMED) {
            throw ApiException.conflict("仅已确认状态可标记付款");
        }
        sb.setStatus(SettlementStatus.PAID);
        sb.setPaidAt(LocalDateTime.now());
        settlementRepository.save(sb);

        // 批次状态推进为已结算（仅在库为 0 的批次；冻结批次不在明细中，不会被误置）
        for (SettlementItem si : settlementItemRepository.findBySettlementId(sb.getId())) {
            ProductBatch b = si.getBatch();
            if (b.getStatus() != BatchStatus.SETTLED) {
                b.setStatus(BatchStatus.SETTLED);
                batchRepository.save(b);
            }
        }
        Supplier supplier = sb.getSupplier();
        supplier.setBalancePayable(supplier.getBalancePayable().subtract(sb.getPayableAmount()));
        supplierRepository.save(supplier);
        return sb;
    }

    @Transactional(readOnly = true)
    public List<SettlementItem> items(Long settlementId) {
        return settlementItemRepository.findBySettlementId(settlementId);
    }

    private SettlementBatch must(String no) {
        return settlementRepository.findBySettlementNo(no)
                .orElseThrow(() -> ApiException.notFound("结算单不存在"));
    }

    private String genNo(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "")
                + String.format("%04d", System.nanoTime() % 10000);
    }
}
