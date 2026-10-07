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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 盘点差异调查集成测试（H2）：
 * 拆账不归全责给补货员、无法确认挂起、仅冻结相关批次、温控食品安全先行、
 * 补货员评分/供应商账单/算法样本分流、补传照片/重复盘点/跨班次/已结算订单接回、
 * 改判冲销留痕、结算跳过冻结批次与红冲报损。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestKafkaConfig.class)
class DiscrepancyInvestigationTest {

    @Autowired private TestFixtures fx;
    @Autowired private RestockService restockService;
    @Autowired private SessionSettlementService sessionService;
    @Autowired private TemperatureService temperatureService;
    @Autowired private StocktakeService stocktakeService;
    @Autowired private DiscrepancyService discrepancyService;
    @Autowired private SettlementService settlementService;
    @Autowired private ChannelStockRepository stockRepository;
    @Autowired private LossRecordRepository lossRepository;
    @Autowired private ProductBatchRepository batchRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private StocktakeOrderRepository stocktakeOrderRepository;

    private record Ctx(AppUser replenisher, Supplier supplier, Product product, Cabinet cabinet,
                       Channel channel, ProductBatch batch, String restockNo) {}

    private Ctx restock(int qty, String channelCode) {
        AppUser user = fx.user("REPLENISHER");
        Supplier supplier = fx.supplier();
        Product product = fx.chilledProduct(supplier);
        Point point = fx.point(LocationType.COMMUNITY);
        Cabinet cabinet = fx.chilledCabinet(point);
        Channel channel = fx.channel(cabinet, product, channelCode);
        ProductBatch batch = fx.batch(product, 7, 0);
        RestockOrder order = restockService.arrive(cabinet.getCabinetNo(), user.getUsername(),
                "https://img.io/arrive.jpg", null);
        restockService.placeItem(order.getOrderNo(), product.getSku(), batch.getBatchNo(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(7),
                qty, channelCode, "https://img.io/milk.jpg", 4.0);
        restockService.complete(order.getOrderNo(), null);
        return new Ctx(user, supplier, product, cabinet, channel, batch, order.getOrderNo());
    }

    /** 开门识别(未关门,作为漏单证据) -> 温控越限 -> 盘点实盘 countedQty，开立差异单 */
    private DiscrepancyOrder openDiscrepancy(Ctx c, int countedQty, String sessionNo) {
        sessionService.openDoor(c.cabinet.getCabinetNo(), sessionNo, "U-X", "漏单用户",
                java.time.LocalDateTime.now());
        sessionService.recordRecognition(sessionNo, c.cabinet.getCabinetNo(), "A01",
                c.product.getSku(), null, "VISION", "PICK", 1, 0.9, null,
                "https://video.io/x.mp4", 0, 3, null, null);
        temperatureService.record(c.cabinet, 12.0, java.time.LocalDateTime.now());

        StocktakeOrder st = stocktakeService.create(c.cabinet.getCabinetNo(),
                c.replenisher.getUsername());
        stocktakeService.count(st.getOrderNo(), List.of(
                new StocktakeService.CountLine("A01", c.batch.getBatchNo(), countedQty)));
        return discrepancyService.openFromStocktake(st.getOrderNo(), "A01",
                c.batch.getBatchNo(), "operator_x");
    }

    /** 一次正常关门结算购物（柜机未锁定时），返回已扣款订单 */
    private OrderRecord normalSale(Ctx c, String sessionNo, String channelCode) {
        sessionService.openDoor(c.cabinet.getCabinetNo(), sessionNo, "U-P", "买家",
                java.time.LocalDateTime.now());
        sessionService.recordRecognition(sessionNo, c.cabinet.getCabinetNo(), channelCode,
                c.product.getSku(), null, "VISION", "PICK", 1, 0.99, null, null, null, null, null, null);
        sessionService.recordRecognition(sessionNo, c.cabinet.getCabinetNo(), channelCode,
                c.product.getSku(), null, "RFID", "PICK", 1, 0.99, null, null, null, null, null, null);
        return sessionService.closeDoor(sessionNo, 260, java.time.LocalDateTime.now());
    }

    @Test
    void 差异按时间证据拆账不把全部差额记给补货员且挂起无法确认部分() {
        Ctx c = restock(10, "A01");
        DiscrepancyOrder dc = openDiscrepancy(c, 6, "S-SPLIT");

        assertThat(dc.getShortageQty()).isEqualTo(4);
        assertThat(dc.getStatus()).isEqualTo(DiscrepancyStatus.INVESTIGATING);
        // 查清前只冻结相关批次
        assertThat(batchRepository.findById(c.batch.getId()).orElseThrow().isSettlementFrozen()).isTrue();

        // 证据视图：补货前后库存、补货照片、开门、识别、温控曲线齐备
        DiscrepancyEvidenceView ev = discrepancyService.evidence(dc.getDiscrepancyNo());
        assertThat(ev.restockItems()).hasSize(1);
        assertThat(ev.doorSessions()).hasSize(1);
        assertThat(ev.recognitionEvents()).hasSize(1);
        assertThat(ev.tempLogs()).isNotEmpty();
        assertThat(ev.evidenceLinks())
                .anyMatch(e -> e.getType() == EvidenceType.STOCK_AFTER_RESTOCK)
                .anyMatch(e -> e.getType() == EvidenceType.RESTOCK_PHOTO)
                .anyMatch(e -> e.getType() == EvidenceType.TEMP_LOG);

        // 4 瓶：补货少放 1 + 识别漏单 1 + 温度损耗 1，剩 1 瓶证据不足挂起
        DiscrepancyOrder after = discrepancyService.allocate(dc.getDiscrepancyNo(), "operator_x",
                List.of(
                        new DiscrepancyService.AllocateLine("RESTOCK_SHORT", 1, null, null,
                                null, c.restockNo, "上架照片只能数出 9 瓶"),
                        new DiscrepancyService.AllocateLine("RECOGNITION_MISS", 1, null, null,
                                "DOOR_SESSION", "S-SPLIT", "开门有拿起视频但无订单"),
                        new DiscrepancyService.AllocateLine("TEMP_ABUSE", 1, null, null,
                                null, null, "温控越限变质报废")),
                "三种成因各 1 瓶，余 1 瓶待查");

        assertThat(after.getStatus()).isEqualTo(DiscrepancyStatus.PENDING_REVIEW);
        assertThat(after.getAllocatedQty()).isEqualTo(3);
        assertThat(after.getPendingQty()).isEqualTo(1);
        // 仅对已确认 3 瓶订正库存（10-3=7），挂起 1 瓶不动
        assertThat(stockRepository.findByChannelId(c.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(7);

        var causes = discrepancyService.causes(dc.getDiscrepancyNo());
        assertThat(causes).anyMatch(x -> x.getCause() == DiscrepancyCause.RESTOCK_SHORT
                && x.getLiability().equals("REPLENISHER"));
        assertThat(causes).anyMatch(x -> x.getCause() == DiscrepancyCause.PENDING
                && x.getStatus() == CauseStatus.PENDING);

        // 报损：识别漏单(平台) + 温控(运营)，补货少放不记供应商报损
        List<LossRecord> losses = lossRepository.findByBatchIdOrderByIdAsc(c.batch.getId());
        assertThat(losses).hasSize(2);
        assertThat(losses).extracting(LossRecord::getReason)
                .containsExactlyInAnyOrder(LossReason.RECOGNITION_MISS, LossReason.TEMP_ABUSE);
        assertThat(losses).anyMatch(l -> l.getReason() == LossReason.RECOGNITION_MISS
                && l.getLiability().equals("PLATFORM"));

        // 下游分流：补货员结算待办、算法样本待办、食品安全已先行处置
        var followUps = discrepancyService.followUps(dc.getDiscrepancyNo());
        assertThat(followUps).anyMatch(f -> f.getType() == FollowUpType.REPLENISHER_SETTLEMENT
                && f.getStatus() == FollowUpStatus.PENDING);
        assertThat(followUps).anyMatch(f -> f.getType() == FollowUpType.ALGORITHM_SAMPLE
                && f.getStatus() == FollowUpStatus.PENDING);
        assertThat(followUps).anyMatch(f -> f.getType() == FollowUpType.FOOD_SAFETY_REVIEW
                && f.getStatus() == FollowUpStatus.DONE);

        // 补货员只为自己那 1 瓶负责：扣 2 分、责任量 1
        AppUser replenisher = userRepository.findById(c.replenisher.getId()).orElseThrow();
        assertThat(replenisher.getScore()).isEqualTo(98);
        assertThat(replenisher.getLiabilityQty()).isEqualTo(1);
    }

    @Test
    void 其他批次在差异调查期间照常结算而冻结批次被跳过() {
        Ctx c = restock(10, "A01");
        DiscrepancyOrder dc = openDiscrepancy(c, 6, "S-FREEZE");
        assertThat(batchRepository.findById(c.batch.getId()).orElseThrow().isSettlementFrozen()).isTrue();

        // 同供应商另一批次在另一（未锁定）柜机正常售卖 1 件
        Cabinet cab2 = fx.chilledCabinet(fx.point(LocationType.OFFICE));
        Channel ch2 = fx.channel(cab2, c.product, "A01");
        ProductBatch b2 = fx.batch(c.product, 7, 0);
        RestockOrder rs2 = restockService.arrive(cab2.getCabinetNo(),
                c.replenisher.getUsername(), null, null);
        restockService.placeItem(rs2.getOrderNo(), c.product.getSku(), b2.getBatchNo(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(7),
                5, "A01", null, 4.0);
        restockService.complete(rs2.getOrderNo(), null);
        String sn = "S-SALE-" + System.nanoTime();
        sessionService.openDoor(cab2.getCabinetNo(), sn, "U-S", "买家", null);
        sessionService.recordRecognition(sn, cab2.getCabinetNo(), "A01",
                c.product.getSku(), null, "VISION", "PICK", 1, 0.99, null, null, null, null, null, null);
        sessionService.recordRecognition(sn, cab2.getCabinetNo(), "A01",
                c.product.getSku(), null, "RFID", "PICK", 1, 0.99, null, null, null, null, null, null);
        sessionService.closeDoor(sn, 260, null);

        LocalDate today = LocalDate.now();
        SettlementBatch stl = settlementService.generate(c.supplier.getId(),
                today.minusDays(1), today.plusDays(1));
        // 冻结批次不在结算明细，只有 b2 售出 1 件
        assertThat(stl.getSoldQty()).isEqualTo(1);
        assertThat(stl.getLossQty()).isZero();
        assertThat(stl.getPayableAmount()).isEqualByComparingTo("2.80");
        assertThat(stl.getRemark()).contains(c.batch.getBatchNo());
    }

    @Test
    void 改判冲销旧责任与报损并回退补货员评分且全程留痕() {
        Ctx c = restock(10, "A01");
        DiscrepancyOrder dc = openDiscrepancy(c, 6, "S-REJUDGE");
        // 一审判补货员全责
        discrepancyService.allocate(dc.getDiscrepancyNo(), "op",
                List.of(new DiscrepancyService.AllocateLine("RESTOCK_SHORT", 4, null,
                        null, null, c.restockNo, "初判")), "初判补货少放");
        assertThat(userRepository.findById(c.replenisher.getId()).orElseThrow().getScore()).isEqualTo(92);
        assertThat(batchRepository.findById(c.batch.getId()).orElseThrow().isSettlementFrozen()).isFalse();

        // 补传照片接回
        discrepancyService.attachPhoto(dc.getDiscrepancyNo(), c.replenisher.getUsername(),
                "https://img.io/proof.jpg", "到货整箱照");

        // 改判：补货少放 2 + 识别漏单 2
        DiscrepancyOrder re = discrepancyService.rejudge(dc.getDiscrepancyNo(), "op",
                List.of(
                        new DiscrepancyService.AllocateLine("RESTOCK_SHORT", 2, null, null,
                                null, c.restockNo, "照片佐证实放 8 瓶"),
                        new DiscrepancyService.AllocateLine("RECOGNITION_MISS", 2, null, null,
                                "DOOR_SESSION", "S-REJUDGE", "两次漏单")),
                "改判");
        assertThat(re.getStatus()).isEqualTo(DiscrepancyStatus.RE_JUDGED);
        assertThat(re.getPendingQty()).isZero();
        // 库存：冲销回补 10 后再扣 4 = 6
        assertThat(stockRepository.findByChannelId(c.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(6);
        // 补货员评分回退后只承担 2 瓶：100-4=96
        assertThat(userRepository.findById(c.replenisher.getId()).orElseThrow().getScore()).isEqualTo(96);
        assertThat(userRepository.findById(c.replenisher.getId()).orElseThrow().getLiabilityQty())
                .isEqualTo(2);
        // 旧补货员条目 REVERSED，识别漏单报损 PLATFORM
        var causes = discrepancyService.causes(dc.getDiscrepancyNo());
        assertThat(causes).anyMatch(x -> x.getCause() == DiscrepancyCause.RESTOCK_SHORT
                && x.getStatus() == CauseStatus.REVERSED && x.getJudgeRound() == 1);
        assertThat(causes).anyMatch(x -> x.getCause() == DiscrepancyCause.RECOGNITION_MISS
                && x.getStatus() == CauseStatus.CONFIRMED && x.getJudgeRound() == 2);
        assertThat(lossRepository.findByBatchIdOrderByIdAsc(c.batch.getId()))
                .anyMatch(l -> l.getReason() == LossReason.RECOGNITION_MISS && !l.isReversed());
        // 流水记录了补传照片与改判
        assertThat(discrepancyService.traces(dc.getDiscrepancyNo()))
                .anyMatch(t -> t.getAction().equals("PHOTO_UPLOAD"))
                .anyMatch(t -> t.getAction().equals("RE_JUDGE"));
    }

    @Test
    void 重复盘点改变数量时原拆账回退维持冻结待改判() {
        Ctx c = restock(10, "A01");
        DiscrepancyOrder dc = openDiscrepancy(c, 6, "S-RECOUNT");
        discrepancyService.allocate(dc.getDiscrepancyNo(), "op",
                List.of(new DiscrepancyService.AllocateLine("RESTOCK_SHORT", 4, null,
                        null, null, null, null)), "初判");
        // 重复盘点：实盘 8（实际只少 2）
        DiscrepancyOrder back = discrepancyService.attachRecount(dc.getDiscrepancyNo(), "op", 8, "复点");
        assertThat(back.getStatus()).isEqualTo(DiscrepancyStatus.INVESTIGATING);
        assertThat(back.getShortageQty()).isEqualTo(2);
        assertThat(back.getPendingQty()).isEqualTo(2);
        // 库存回补到 10，批次重新冻结
        assertThat(stockRepository.findByChannelId(c.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(10);
        assertThat(batchRepository.findById(c.batch.getId()).orElseThrow().isSettlementFrozen()).isTrue();

        // 改判补货少放 2
        DiscrepancyOrder re = discrepancyService.rejudge(dc.getDiscrepancyNo(), "op",
                List.of(new DiscrepancyService.AllocateLine("RESTOCK_SHORT", 2, null,
                        null, null, null, "复点后确认")), "复点改判");
        assertThat(re.getStatus()).isEqualTo(DiscrepancyStatus.RE_JUDGED);
        assertThat(stockRepository.findByChannelId(c.channel.getId()).get(0).getQtyOnHand())
                .isEqualTo(8);
    }

    @Test
    void 跨班次补货与已结算订单都能接回原差异单() {
        Ctx c = restock(10, "A01");
        // 先有一笔正常已结算订单（历史订单，差异调查需接回核对）
        OrderRecord paid = normalSale(c, "S-SETTLED-HIST", "A01");

        DiscrepancyOrder dc = openDiscrepancy(c, 6, "S-LINK");

        // 另一班次补货单（同柜机同批次）
        RestockOrder cross = restockService.arrive(c.cabinet.getCabinetNo(),
                c.replenisher.getUsername(), null, "夜班补货");
        restockService.placeItem(cross.getOrderNo(), c.product.getSku(), c.batch.getBatchNo(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(7),
                2, "A01", "https://img.io/night.jpg", 4.0);
        restockService.complete(cross.getOrderNo(), null);
        discrepancyService.attachCrossShiftRestock(dc.getDiscrepancyNo(), "op",
                cross.getOrderNo(), "夜班补放 2 瓶");

        // 已结算订单接回
        discrepancyService.attachSettledOrder(dc.getDiscrepancyNo(), "finance",
                paid.getOrderNo(), "核对是否漏扣");

        var traces = discrepancyService.traces(dc.getDiscrepancyNo());
        assertThat(traces).anyMatch(t -> t.getAction().equals("CROSS_SHIFT_RESTOCK"));
        assertThat(traces).anyMatch(t -> t.getAction().equals("SETTLED_ORDER_LINK"));
        DiscrepancyEvidenceView ev = discrepancyService.evidence(dc.getDiscrepancyNo());
        assertThat(ev.evidenceLinks())
                .anyMatch(e -> e.getType() == EvidenceType.SETTLED_ORDER)
                .anyMatch(e -> e.getBizNo() != null && e.getBizNo().equals(cross.getOrderNo()));
    }

    @Test
    void 拆账数量超过盘亏应拒绝() {
        Ctx c = restock(10, "A01");
        DiscrepancyOrder dc = openDiscrepancy(c, 6, "S-OVER");
        assertThatThrownBy(() -> discrepancyService.allocate(dc.getDiscrepancyNo(), "op",
                List.of(new DiscrepancyService.AllocateLine("RESTOCK_SHORT", 5, null,
                        null, null, null, null)), "超量"))
                .hasMessageContaining("超过盘亏");
    }

    @Test
    void 改判红冲的供应商报损不再计入结算() {
        Ctx c = restock(10, "A01");
        // 系统 10 实盘 9，盘亏 1
        StocktakeOrder st = stocktakeService.create(c.cabinet.getCabinetNo(), c.replenisher.getUsername());
        stocktakeService.count(st.getOrderNo(), List.of(
                new StocktakeService.CountLine("A01", c.batch.getBatchNo(), 9)));
        DiscrepancyOrder dc = discrepancyService.openFromStocktake(st.getOrderNo(), "A01",
                c.batch.getBatchNo(), "op");
        // 初判供应商责任报损漏记
        discrepancyService.allocate(dc.getDiscrepancyNo(), "op",
                List.of(new DiscrepancyService.AllocateLine("LOSS_MISSED", 1, "SUPPLIER",
                        null, null, null, "初判供方药损")), "初判");
        LossRecord first = lossRepository.findByBatchIdOrderByIdAsc(c.batch.getId()).get(0);
        assertThat(first.getLiability()).isEqualTo("SUPPLIER");
        assertThat(first.isReversed()).isFalse();

        // 改判为补货少放 → 原供应商报损红冲，批次解冻
        discrepancyService.rejudge(dc.getDiscrepancyNo(), "op",
                List.of(new DiscrepancyService.AllocateLine("RESTOCK_SHORT", 1, null,
                        null, null, null, "改判补货少放")), "改判");
        assertThat(lossRepository.findById(first.getId()).orElseThrow().isReversed()).isTrue();
        assertThat(batchRepository.findById(c.batch.getId()).orElseThrow().isSettlementFrozen()).isFalse();

        LocalDate today = LocalDate.now();
        SettlementBatch stl = settlementService.generate(c.supplier.getId(),
                today.minusDays(1), today.plusDays(1));
        assertThat(stl.getLossQty()).isZero();
    }
}
