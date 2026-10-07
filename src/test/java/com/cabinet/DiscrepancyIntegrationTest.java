package com.cabinet;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.*;
import com.cabinet.dto.DiscrepancyEvidenceView;
import com.cabinet.repo.*;
import com.cabinet.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 盘点差异核查集成测试（H2）：
 * 四瓶差异按时间/证据拆分（识别漏单/补货少放/挂起）、批次结算冻结、其他商品照常结算、
 * 温控异常食品安全先行处置、补传照片/重复盘点/已结算订单接回、改判冲销与责任转移、
 * 补货员结算扣罚与评分、供应商对账调整核销。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestKafkaConfig.class)
class DiscrepancyIntegrationTest {

    @Autowired private TestFixtures fx;
    @Autowired private RestockService restockService;
    @Autowired private SessionSettlementService sessionService;
    @Autowired private StocktakeService stocktakeService;
    @Autowired private DiscrepancyService discrepancyService;
    @Autowired private SettlementService settlementService;
    @Autowired private TemperatureService temperatureService;

    @Autowired private StocktakeOrderRepository stocktakeRepository;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private ChannelStockRepository stockRepository;
    @Autowired private LossRecordRepository lossRepository;
    @Autowired private DiscrepancyAllocationRepository allocationRepository;
    @Autowired private ReplenisherPenaltyRepository penaltyRepository;
    @Autowired private RecognitionSampleRepository sampleRepository;
    @Autowired private ReconAdjustmentRepository reconRepository;
    @Autowired private FoodSafetyReviewRepository foodSafetyRepository;
    @Autowired private DiscrepancyFollowUpRepository followUpRepository;
    @Autowired private SettlementItemRepository settlementItemRepository;

    private record Scene(AppUser replenisher, AppUser operator, Supplier supplier,
                         Product product, Cabinet cabinet, Channel channel, ProductBatch batch,
                         String restockNo) {}

    private Scene restock(int qty, String channelCode) {
        AppUser replenisher = fx.user("REPLENISHER");
        AppUser operator = fx.user("OPERATOR");
        Supplier supplier = fx.supplier();
        Product product = fx.chilledProduct(supplier);
        Point point = fx.point(LocationType.COMMUNITY);
        Cabinet cabinet = fx.chilledCabinet(point);
        Channel channel = fx.channel(cabinet, product, channelCode);
        ProductBatch batch = fx.batch(product, 7, 0);

        RestockOrder order = restockService.arrive(cabinet.getCabinetNo(),
                replenisher.getUsername(), "https://img.io/before.jpg", "早班补货");
        restockService.placeItem(order.getOrderNo(), product.getSku(), batch.getBatchNo(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(7),
                qty, channelCode, "https://img.io/milk.jpg", 4.0);
        restockService.complete(order.getOrderNo(), null);
        return new Scene(replenisher, operator, supplier, product, cabinet, channel, batch,
                order.getOrderNo());
    }

    /** 盘点并从差异行立案 */
    private DiscrepancyOrder countAndOpen(Scene d, int systemQty, int countedQty) {
        StocktakeOrder st = stocktakeService.create(d.cabinet.getCabinetNo(),
                d.operator.getUsername());
        stocktakeService.count(st.getOrderNo(), List.of(
                new StocktakeService.CountLine("A01", d.batch.getBatchNo(), countedQty)));
        return discrepancyService.openFromStocktake(st.getOrderNo(), "A01",
                d.batch.getBatchNo(), d.operator.getUsername());
    }

    @Test
    void 四瓶差异按证据拆分_挂起部分冻结批次_其他商品照常结算() {
        Scene d = restock(10, "A01");

        // 一次开门：无任何识别事件，但称重净少 260g（1 瓶）-> 识别漏单候选
        String sessionNo = "S-MISS-" + System.nanoTime();
        sessionService.openDoor(d.cabinet.getCabinetNo(), sessionNo, "U001", "漏单用户", null);
        OrderRecord cancelled = sessionService.closeDoor(sessionNo, 260, null);
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        // 系统 10 实盘 6，差 4
        DiscrepancyOrder dc = countAndOpen(d, 10, 6);
        assertThat(dc.getDiffQty()).isEqualTo(4);
        assertThat(dc.isSettlementFrozen()).isTrue();

        // 证据视图：补货照片/补货单在场，候选原因含识别漏单
        DiscrepancyEvidenceView ev = discrepancyService.evidence(dc.getDiscrepancyNo());
        assertThat(ev.restockItems()).isNotEmpty();
        assertThat(ev.restockOrders().get(0).getPhotoUrls()).contains("before.jpg");
        assertThat(ev.candidateCauses())
                .anyMatch(c -> c.cause().equals(DiscrepancyCause.RECOGNITION_MISSED.name()));

        // 立案后旧的一刀切盘亏调整必须被拒绝
        StocktakeOrder st = dc.getStocktake();
        assertThatThrownBy(() -> stocktakeService.adjust(st.getOrderNo(), true, "整额盘亏"))
                .hasMessageContaining("差异核查单");

        LocalDateTime now = LocalDateTime.now();
        // 拆分：识别漏单 1（平台） + 补货少放 2（补货员），剩 1 瓶无法确认挂起
        discrepancyService.allocate(dc.getDiscrepancyNo(), List.of(
                new DiscrepancyService.AllocateLine(DiscrepancyCause.RECOGNITION_MISSED,
                        LiabilityParty.PLATFORM, 1, now, null, sessionNo, null, null, null,
                        "称重少 260g 无订单，识别漏单"),
                new DiscrepancyService.AllocateLine(DiscrepancyCause.RESTOCK_SHORTAGE,
                        LiabilityParty.REPLENISHER, 2, now, null, null, d.restockNo(), null, null,
                        "照片比对少放 2 瓶"),
                new DiscrepancyService.AllocateLine(DiscrepancyCause.UNCONFIRMED,
                        LiabilityParty.NONE, 1, now, null, null, null, null, null,
                        "剩余 1 瓶证据不足，挂起复核")), d.operator.getUsername());

        DiscrepancyOrder partial = discrepancyService.must(dc.getDiscrepancyNo());
        assertThat(partial.getStatus()).isEqualTo(DiscrepancyStatus.PARTIALLY_CONFIRMED);
        assertThat(partial.getConfirmedQty()).isEqualTo(3);
        assertThat(partial.getSuspendedQty()).isEqualTo(1);
        assertThat(partial.isSettlementFrozen()).isTrue();
        // 库存仅扣减已确认的 3 瓶，挂起的 1 瓶不动
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(7);

        // 平台责任：识别样本回流；补货员责任：罚则 + 评分 100-2*5=90
        assertThat(sampleRepository.findByReviewedFalse())
                .anyMatch(s -> s.getQty() == 1 && s.getLabel().equals("RECOGNITION_MISSED"));
        ReplenisherPenalty penalty = penaltyRepository
                .findByReplenisherIdAndStatus(d.replenisher.getId(), PenaltyStatus.PENDING)
                .stream().findFirst().orElseThrow();
        assertThat(penalty.getQty()).isEqualTo(2);
        assertThat(penalty.getDeductAmount()).isEqualByComparingTo("5.60");
        assertThat(appUserRepository.findById(d.replenisher.getId()).orElseThrow().getScorePoints()).isEqualTo(90);

        // 冻结期间生成供应商结算：该批次整批跳过，但其他商品照常结算
        Product other = fx.chilledProduct(d.supplier);
        Channel ch2 = fx.channel(d.cabinet, other, "A02");
        ProductBatch b2 = fx.batch(other, 10, 0);
        RestockOrder rs2 = restockService.arrive(d.cabinet.getCabinetNo(),
                d.replenisher.getUsername(), null, null);
        restockService.placeItem(rs2.getOrderNo(), other.getSku(), b2.getBatchNo(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(10),
                5, "A02", null, 4.0);
        restockService.complete(rs2.getOrderNo(), null);
        // 其他商品售出 2 件
        String s2 = "S-OK-" + System.nanoTime();
        sessionService.openDoor(d.cabinet.getCabinetNo(), s2, "U002", "正常用户", null);
        sessionService.recordRecognition(s2, d.cabinet.getCabinetNo(), "A02",
                other.getSku(), null, "VISION", "PICK", 2, 0.99, null, null, null, null, null, null);
        sessionService.recordRecognition(s2, d.cabinet.getCabinetNo(), "A02",
                other.getSku(), null, "RFID", "PICK", 2, 0.99, null, null, null, null, null, null);
        sessionService.closeDoor(s2, other.getUnitWeightGram() * 2, null);

        LocalDate today = LocalDate.now();
        SettlementBatch stl = settlementService.generate(d.supplier.getId(),
                today.minusDays(1), today.plusDays(1));
        assertThat(stl.getHeldBatchCount()).isEqualTo(1);
        assertThat(stl.getHeldBatchNos()).contains(d.batch.getBatchNo());
        List<SettlementItem> items = settlementItemRepository.findBySettlementId(stl.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getBatch().getId()).isEqualTo(b2.getId());
        assertThat(items.get(0).getSoldQty()).isEqualTo(2);
        // 冻结批次不能进入已确认结算
        settlementService.confirm(stl.getSettlementNo());

        // 挂起的 1 瓶经监控复核为报损漏记（运营责任）-> 全部定责，结单解冻
        DiscrepancyAllocation suspended = allocationRepository
                .findByDiscrepancyIdAndStatus(dc.getId(), AllocationStatus.SUSPENDED)
                .stream().findFirst().orElseThrow();
        discrepancyService.rejudge(dc.getDiscrepancyNo(), suspended.getId(),
                new DiscrepancyService.AllocateLine(DiscrepancyCause.LOSS_MISSED,
                        LiabilityParty.NONE, 1, now, null, null, null, null, null,
                        "监控确认破损已丢弃，补登报损"),
                d.operator.getUsername());
        DiscrepancyOrder resolved = discrepancyService.must(dc.getDiscrepancyNo());
        assertThat(resolved.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(resolved.isSettlementFrozen()).isFalse();
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(6);
        // 盘点单随之收口
        assertThat(stocktakeRepository.findById(st.getId()).orElseThrow().getStatus())
                .isEqualTo(StocktakeStatus.ADJUSTED);

        // 补货员结算扣款落地
        var result = discrepancyService.settleReplenisher(d.replenisher.getId(),
                d.operator.getUsername());
        assertThat(result.get("totalDeductAmount")).isEqualTo(new BigDecimal("5.60"));
        assertThat(penaltyRepository.findById(penalty.getId()).orElseThrow().getStatus())
                .isEqualTo(PenaltyStatus.DEDUCTED);
    }

    @Test
    void 温控异常商品食品安全先行处置不等追责结束() {
        Scene d = restock(10, "A01");
        // 温控越限锁定，持续 31 分钟隔离柜内 10 瓶
        temperatureService.record(d.cabinet, 12.0, LocalDateTime.now());
        temperatureService.record(d.cabinet, 13.0, LocalDateTime.now().plusMinutes(31));
        ChannelStock s = stockRepository.findByChannelId(d.channel.getId()).get(0);
        assertThat(s.getQtyQuarantined()).isEqualTo(10);

        // 盘点差 4 并立案：自动建立食安待处置单
        DiscrepancyOrder dc = countAndOpen(d, 10, 6);
        FoodSafetyReview review = discrepancyService.foodSafetyReviews(dc.getDiscrepancyNo())
                .stream().filter(r -> r.getStatus() == FoodSafetyStatus.PENDING).findFirst()
                .orElseThrow();
        assertThat(review.getQty()).isEqualTo(10);
        assertThat(review.getPeakTempC()).isGreaterThanOrEqualTo(13.0);

        // 责任尚未拆分时，先报废 1 瓶（温控损耗）
        FoodSafetyReview handled = discrepancyService.handleFoodSafety(dc.getDiscrepancyNo(),
                1, FoodSafetyStatus.DISPOSED, Disposition.SCRAP,
                d.operator.getUsername(), "感官变质销毁 1 瓶");
        assertThat(handled.getStatus()).isEqualTo(FoodSafetyStatus.DISPOSED);
        // 其余 9 瓶经盘点实物完好，自动解除隔离
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyQuarantined())
                .isZero();
        // 温控报损已记账
        assertThat(lossRepository.findByBatchIdOrderByIdAsc(d.batch.getId()))
                .anyMatch(l -> l.getReason() == LossReason.TEMP_ABUSE && l.getQty() == 1
                        && !l.isVoided());
        // 差异单此时仅部分确认（食安 1 瓶先行），仍在核查
        assertThat(discrepancyService.must(dc.getDiscrepancyNo()).getStatus())
                .isEqualTo(DiscrepancyStatus.PARTIALLY_CONFIRMED);

        // 剩余 3 瓶：补货少放 2 + 识别漏单 1
        discrepancyService.allocate(dc.getDiscrepancyNo(), List.of(
                new DiscrepancyService.AllocateLine(DiscrepancyCause.RESTOCK_SHORTAGE,
                        LiabilityParty.REPLENISHER, 2, LocalDateTime.now(),
                        null, null, d.restockNo(), null, null, "少放 2 瓶"),
                new DiscrepancyService.AllocateLine(DiscrepancyCause.RECOGNITION_MISSED,
                        LiabilityParty.PLATFORM, 1, LocalDateTime.now(),
                        null, null, null, null, null, "漏识别 1 瓶")),
                d.operator.getUsername());
        DiscrepancyOrder done = discrepancyService.must(dc.getDiscrepancyNo());
        assertThat(done.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(6);
    }

    @Test
    void 补传照片接回后改判_责任由补货员转供应商并核销对账() {
        Scene d = restock(10, "A01");
        DiscrepancyOrder dc = countAndOpen(d, 10, 8); // 差 2
        discrepancyService.allocate(dc.getDiscrepancyNo(), List.of(
                new DiscrepancyService.AllocateLine(DiscrepancyCause.RESTOCK_SHORTAGE,
                        LiabilityParty.REPLENISHER, 2, LocalDateTime.now(),
                        null, null, d.restockNo(), null, null, "初判补货少放")),
                d.operator.getUsername());
        assertThat(discrepancyService.must(dc.getDiscrepancyNo()).getStatus())
                .isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(appUserRepository.findById(d.replenisher.getId()).orElseThrow().getScorePoints()).isEqualTo(90);

        // 供应商提供出库监控后补货员补传照片，结单被接回并重新冻结
        discrepancyService.attachFollowUp(dc.getDiscrepancyNo(), FollowUpType.PHOTO_SUPPLEMENT,
                d.restockNo(), "https://img.io/supplier-proof.jpg", 0,
                d.replenisher.getUsername(), "供应商出库满载，补传交接照片");
        DiscrepancyOrder reopened = discrepancyService.must(dc.getDiscrepancyNo());
        assertThat(reopened.getStatus()).isEqualTo(DiscrepancyStatus.REOPENED);
        assertThat(reopened.isSettlementFrozen()).isTrue();

        // 改判为供应商到货短少
        Long oldId = discrepancyService.allocations(dc.getDiscrepancyNo()).get(0).getId();
        DiscrepancyAllocation now = discrepancyService.rejudge(dc.getDiscrepancyNo(), oldId,
                new DiscrepancyService.AllocateLine(DiscrepancyCause.RESTOCK_SHORTAGE,
                        LiabilityParty.SUPPLIER, 2, LocalDateTime.now(),
                        null, null, d.restockNo(), null, null, "改判供应商到货短少"),
                d.operator.getUsername());
        DiscrepancyAllocation old = allocationRepository.findById(oldId).orElseThrow();
        assertThat(now.getLiability()).isEqualTo(LiabilityParty.SUPPLIER);
        assertThat(now.getStatus()).isEqualTo(AllocationStatus.CONFIRMED);
        // 旧分摊作废、旧报损 voided、补货员罚则撤销且评分回补
        assertThat(old.getStatus()).isEqualTo(AllocationStatus.VOIDED);
        assertThat(lossRepository.findByBatchIdOrderByIdAsc(d.batch.getId()))
                .anyMatch(l -> l.isVoided() && l.getReason() == LossReason.RESTOCK_SHORTAGE);
        assertThat(appUserRepository.findById(d.replenisher.getId()).orElseThrow().getScorePoints()).isEqualTo(100);
        assertThat(penaltyRepository.findByDiscrepancyId(dc.getId())).allSatisfy(p ->
                assertThat(p.getStatus()).isEqualTo(PenaltyStatus.REVERSED));
        // 供应商对账调整生成
        ReconAdjustment adj = reconRepository.findByDiscrepancyId(dc.getId()).stream()
                .filter(a -> !a.isVoided()).findFirst().orElseThrow();
        assertThat(adj.getDirection()).isEqualTo(ReconDirection.DEDUCT);
        assertThat(adj.getAmount()).isEqualByComparingTo("5.60");
        assertThat(discrepancyService.must(dc.getDiscrepancyNo()).getStatus())
                .isEqualTo(DiscrepancyStatus.RESOLVED);

        // 下一期结算纳入对账调整并核销
        LocalDate today = LocalDate.now();
        SettlementBatch stl = settlementService.generate(d.supplier.getId(),
                today.minusDays(1), today.plusDays(1));
        SettlementItem item = settlementItemRepository.findBySettlementId(stl.getId()).stream()
                .filter(i -> i.getBatch().getId().equals(d.batch.getId())).findFirst().orElseThrow();
        assertThat(item.getReconAmount()).isEqualByComparingTo("-5.60");
        assertThat(item.getPayableAmount()).isEqualByComparingTo("-5.60");
        settlementService.confirm(stl.getSettlementNo());
        assertThat(reconRepository.findById(adj.getId()).orElseThrow().isApplied()).isTrue();
    }

    @Test
    void 重复盘点改变差异数量时冲销原判定并回滚库存与罚则() {
        Scene d = restock(10, "A01");
        DiscrepancyOrder dc = countAndOpen(d, 10, 7); // 差 3
        discrepancyService.allocate(dc.getDiscrepancyNo(), List.of(
                new DiscrepancyService.AllocateLine(DiscrepancyCause.RESTOCK_SHORTAGE,
                        LiabilityParty.REPLENISHER, 3, LocalDateTime.now(),
                        null, null, d.restockNo(), null, null, "少放 3 瓶")),
                d.operator.getUsername());
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(7);

        // 复盘确认实盘为 6，差 4：原 3 瓶判定冲销，库存回补到 10，单据重开
        discrepancyService.recount(dc.getDiscrepancyNo(), 6,
                d.operator.getUsername(), "复盘清点 6 瓶");
        DiscrepancyOrder reopened = discrepancyService.must(dc.getDiscrepancyNo());
        assertThat(reopened.getStatus()).isEqualTo(DiscrepancyStatus.REOPENED);
        assertThat(reopened.getDiffQty()).isEqualTo(4);
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(10);
        assertThat(reopened.getVoidedQty()).isEqualTo(3);
        assertThat(appUserRepository.findById(d.replenisher.getId()).orElseThrow().getScorePoints()).isEqualTo(100);
        assertThat(followUpRepository.findByDiscrepancyIdOrderByCreatedAtAsc(dc.getId()))
                .anyMatch(f -> f.getType() == FollowUpType.RECOUNT && f.getQtyChange() == -1);

        // 按新差异重新拆分 4 瓶
        discrepancyService.allocate(dc.getDiscrepancyNo(), List.of(
                new DiscrepancyService.AllocateLine(DiscrepancyCause.RESTOCK_SHORTAGE,
                        LiabilityParty.REPLENISHER, 4, LocalDateTime.now(),
                        null, null, d.restockNo(), null, null, "复盘后定责少放 4 瓶")),
                d.operator.getUsername());
        assertThat(discrepancyService.must(dc.getDiscrepancyNo()).getStatus())
                .isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(stockRepository.findByChannelId(d.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(6);
    }

    @Test
    void 已结算订单事后接回原差异单并支持再次改判() {
        Scene d = restock(10, "A01");
        DiscrepancyOrder dc = countAndOpen(d, 10, 9); // 差 1
        discrepancyService.allocate(dc.getDiscrepancyNo(), List.of(
                new DiscrepancyService.AllocateLine(DiscrepancyCause.UNCONFIRMED,
                        LiabilityParty.NONE, 1, LocalDateTime.now(),
                        null, null, null, null, null, "证据不足挂起")),
                d.operator.getUsername());
        // 挂起 -> 部分确认，批次仍冻结
        assertThat(discrepancyService.must(dc.getDiscrepancyNo()).getStatus())
                .isEqualTo(DiscrepancyStatus.PARTIALLY_CONFIRMED);

        // 事后发现一笔已结算订单其实未扣款（销售未扣减），接回原单
        discrepancyService.attachFollowUp(dc.getDiscrepancyNo(), FollowUpType.SETTLED_ORDER,
                "OD-SETTLED-1", null, 1, d.operator.getUsername(),
                "已结算订单关联本批次，复核发现销售未扣减");
        DiscrepancyAllocation suspended = discrepancyService.allocations(dc.getDiscrepancyNo())
                .stream().filter(a -> a.getStatus() == AllocationStatus.SUSPENDED).findFirst()
                .orElseThrow();
        discrepancyService.rejudge(dc.getDiscrepancyNo(), suspended.getId(),
                new DiscrepancyService.AllocateLine(DiscrepancyCause.SALE_NOT_DEDUCTED,
                        LiabilityParty.PLATFORM, 1, LocalDateTime.now(),
                        "OD-SETTLED-1", null, null, null, null, "已结算订单未扣减，平台承担"),
                d.operator.getUsername());
        DiscrepancyOrder done = discrepancyService.must(dc.getDiscrepancyNo());
        assertThat(done.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(lossRepository.findByBatchIdOrderByIdAsc(d.batch.getId()))
                .anyMatch(l -> l.getReason() == LossReason.SALE_NOT_DEDUCTED
                        && "PLATFORM".equals(l.getLiability()));
        assertThat(followUpRepository.findByDiscrepancyIdOrderByCreatedAtAsc(dc.getId()))
                .anyMatch(f -> f.getType() == FollowUpType.SETTLED_ORDER
                        && "OD-SETTLED-1".equals(f.getRefNo()));
    }
}
