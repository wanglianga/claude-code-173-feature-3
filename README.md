# 无人货柜补货盘点临期下架服务

面向城市社区、办公楼、医院无人货柜场景的后端服务，覆盖补货员补货上架、柜机多源识别下单、临期/过期下架、报损、冷藏温控、盘点差异调整、用户投诉退款与供应商批次结算的全链路。

技术栈：**Java 17 + Spring Boot 3.3 + Spring Security + Spring Kafka + PostgreSQL + H2(测试)**。

## 一、能力总览

| 业务问题 | 实现方式 |
| --- | --- |
| 补货记录 | 柜机编号、商品批次、保质期（生产/到期日）、数量、货道位置、温度要求、补货照片全部落表（`restock_order`/`restock_item`） |
| 重量/视觉/RFID 识别下单 | 设备事件投递 Kafka `cabinet.device.event`，服务侧按会话聚合多源事件，关门融合后 FEFO 扣批次库存、生成订单与批次台账 |
| 识别错误 | 多源数量不一致/低置信度/称重交叉校验不符 → 订单挂起 `PENDING_REVIEW`，**不扣款不扣库存**，人工复核确认/否定 |
| 拿起又放回 | 每个识别源独立计算“拿起-放回”净值，净值为 0 不出单、不扣款 |
| 货道卡货 | 设备 `CHANNEL_JAM` 事件生成告警；卡货损坏可走手工下架-报废 |
| 冷藏温度异常 | 温度越限即锁定柜机（拒绝开门），持续越限 30 分钟隔离柜内全部批次，食品安全处置（下架/报损） |
| 临期未及时下架 | 每日 02:10 定时扫描（可手动触发）生成临期/过期下架单与告警；执行下架后报废记报损 |
| 放错货道 | 上架时校验货道计划商品，不一致记 `WRONG_CHANNEL` 告警，补货单完成态为 `COMPLETED_MISMATCH` |
| 盘点库存不符 | 盘点单生成系统快照 → 录入实盘 → 差异审批调整：盘亏记报损（SHRINKAGE），台账 ADJUST_MINUS/PLUS |
| 盘点差异查不清 | 差异单关联补货前后库存、补货照片、销售订单、开门记录、报损记录、温控曲线；按发生时间与证据把差额拆到销售漏扣/补货少放/报损漏记/识别漏单/温控损耗，无法确认部分挂起复核 |
| 差异未查清的结算 | 只冻结相关批次的补货结算与供应商对账（`product_batch.settlement_frozen`），其他商品/批次照常结算；结算单备注列出跳过批次 |
| 确认责任后分流 | 补货少放→补货员结算+评分；供方报损→供应商账单；识别漏单→算法样本回流；温控异常→食品安全先行处置（不等普通追责）；改判红冲旧报损并回退评分 |
| 补证/复点/跨班次/已结算订单 | 补传照片、重复盘点、跨班次补货、已结算订单均接回原差异单（`discrepancy_trace` 留痕），数量变化时原拆账回退、可改判，每轮数量与责任变化可回看 |
| 用户误扣款 | 投诉单关联开门会话；客服证据回放后按整单/按行退款；商品收回回补库存（REFUND_RETURN），未收回记报损（REFUND_NO_RETURN） |
| 按点位调品类 | `PUT /api/channels/{id}/planned-product` 调整货道计划商品 |
| 批次全链路追溯 | `batch_ledger` 逐笔记录补货入/售卖出/下架出/报损出/盘盈盘亏/退款回库/结算；`/api/analytics/batches/{batchNo}/trace` |
| 售后证据回放 | 开门记录、多源识别结果（含视频片段 URL 与起止秒）、订单、批次库存变化、温控曲线一次返回 |
| 供应商结算 | 按周期汇总真实售出（扣除退款）与供应商责任报损，逐批次明细，应付 = 售卖成本 − 责任报损 |

### 关键状态机

- **开门会话**：`OPEN → SETTLED`（已扣款）/ `REVIEW_PENDING → SETTLED|CANCELLED`（人工复核）/ `CANCELLED`（无净拿取、温控拒开门）/ `SETTLED_REFUNDED`
- **商品批次**：`IN_STOCK → SOLD_OUT | REMOVED → SETTLED`
- **下架单**：`PENDING → EXECUTED`；去向 `SCRAP`（报废，产生报损）/ `RETURN_SUPPLIER`（退供，不产生报损）
- **结算单**：`DRAFT → CONFIRMED → PAID`，同供应商同周期不可重复生成
- **盘点差异调查单**：`INVESTIGATING → PENDING_REVIEW（有挂起）/ RESOLVED（全查清）`；补证/复点/跨班次/已结算订单接回后可 `RE_JUDGED`，旧拆账条目标 `REVERSED` 但全程留痕

## 二、一键启动（Docker Compose）

```bash
docker compose up --build
```

启动三个容器：PostgreSQL 16（5432）、Kafka 3.9 KRaft 单节点（9092）、本应用（8080）。
应用启动时自动建表（`ddl-auto: update`）并写入种子数据。

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

## 三、本地开发启动（无 Docker）

前置：JDK 17、Maven 3.8+、PostgreSQL、Kafka（KRaft 即可）。

```bash
# 1. 准备数据库（示例为本机 5432）
createdb -U postgres cabinet
# 并通过环境变量覆盖连接信息（默认值见 application.yml）：
export CABINET_DB_URL=jdbc:postgresql://localhost:5432/cabinet
export CABINET_DB_USER=app
export CABINET_DB_PASSWORD=app
export KAFKA_BOOTSTRAP=localhost:9092

# 2. 启动应用（自动建表 + 种子数据）
mvn spring-boot:run
# 若 Kafka/PG 在自定义地址，可使用内置 local profile：
# mvn spring-boot:run -Dspring-boot.run.profiles=local   （见 application-local.yml）
```

## 四、测试账号（HTTP Basic，种子数据）

| 角色 | 用户名 | 密码 | 权限 |
| --- | --- | --- | --- |
| 补货员 | `replenisher` | `cabinet123` | 补货上架、执行下架、盘点 |
| 运营 | `operator` | `cabinet123` | 品类调整、下架、盘点审批、复核、告警、统计 |
| 财务 | `finance` | `cabinet123` | 供应商结算、查看批次损耗 |
| 客服 | `service` | `cabinet123` | 证据回放、投诉判责退款 |
| 管理员 | `admin` | `cabinet123` | 全部 |

种子主数据：供应商 `SUP001 鲜食达`、`SUP002 清泉饮品`；商品 `P-SANDWICH`（冷藏火腿三明治）、`P-MILK`（冷藏鲜牛奶）、`P-WATER`（常温矿泉水）；柜机 `CAB-001`（阳光花园社区，冷藏）、`CAB-002`（云谷科技园，常温）、`CAB-003`（市中心医院，冷藏）。另含一个次日到期的批次 `B-MILK-SEED` 用于演示临期扫描。

## 五、验证方式（核心链路）

> 以下 curl 使用 `sync=true` 同步处理设备事件，便于直接观察结果；生产柜机默认异步走 Kafka（去掉 `sync` 参数即可验证消息链路）。

### 1) 补货上架（批次 + 保质期 + 货道 + 温度 + 照片）

```bash
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/restock/arrive \
  -H 'Content-Type: application/json' \
  -d '{"cabinetNo":"CAB-001","photoUrls":"https://example.io/photos/arrive.jpg","remark":"早班补货"}'
# 返回 orderNo，例如 RS202610060001

curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/restock/RS.../items \
  -H 'Content-Type: application/json' \
  -d '{"sku":"P-MILK","batchNo":"B-MILK-1001","productionDate":"2026-10-05","expiryDate":"2026-10-12","qty":6,"channelCode":"A02","photoUrl":"https://example.io/photos/milk.jpg","cabinetTempC":4.2}'

curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/restock/RS.../complete -d '{}' \
  -H 'Content-Type: application/json'
```

放错货道演示：把 `P-MILK` 放进计划为三明治的货道 `A01`，返回 `warning=WRONG_CHANNEL`，完成后补货单状态为 `COMPLETED_MISMATCH`，`GET /api/alerts` 出现告警。

### 2) 开门购物（多源识别 + 拿起放回）→ 关门结算

```bash
# 开门
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_OPEN","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","userCode":"U1001","userName":"王女士"}'

# 视觉识别拿起 2 瓶
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","channelCode":"A02","sku":"P-MILK","source":"VISION","action":"PICK","qty":2,"confidence":0.95,"videoClipUrl":"https://video.io/s1","clipStartSec":3,"clipEndSec":6}'

# RFID 同样识别 2 瓶（多源一致）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"RECOGNITION","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","channelCode":"A02","sku":"P-MILK","source":"RFID","action":"PICK","qty":2,"confidence":0.99}'

# 关门，柜内净重减少 520g（2×260g，交叉校验一致）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"DOOR_CLOSE","cabinetNo":"CAB-001","sessionNo":"S-DEMO-1","weightDeltaGram":520}'
```

结果：订单 `CHARGED` 11.00 元（2×5.50），A02 库存按批次扣减，批次台账出现 `SALE_OUT`。
若发一条 `PUT` 且与拿起数量相等，则订单 `CANCELLED`；若视觉与重量识别数量冲突，则订单 `PENDING_REVIEW`，经 `POST /api/sessions/{sessionNo}/review/confirm` 复核后才扣款。

### 3) 温控异常

```bash
# 12℃ 超过冷藏上限 8℃ → 柜机锁定
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/device/events?sync=true \
  -H 'Content-Type: application/json' \
  -d '{"type":"TEMP","cabinetNo":"CAB-001","tempC":12.0}'
# 此后 DOOR_OPEN 被拒（会话 CANCELLED，备注“温控报警锁定，开门被拒”）
```

### 4) 临期扫描 → 下架 → 报损

```bash
curl -s -u operator:cabinet123 -X POST 'http://localhost:8080/api/removals/scan-expiry?nearExpiryDays=2'
# 列出下架单（也可在告警中看到临期/过期条目），执行报废：
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/removals/RM.../execute \
  -H 'Content-Type: application/json' -d '{"disposition":"SCRAP","remark":"临期下架报废"}'
```

### 5) 盘点差异调整

```bash
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes \
  -H 'Content-Type: application/json' -d '{"cabinetNo":"CAB-001"}'
# 录入实盘（对某行少盘 1 件）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes/ST.../count \
  -H 'Content-Type: application/json' \
  -d '{"counts":[{"channelCode":"A02","batchNo":"B-MILK-1001","countedQty":3}]}'
# 审批调整（盘亏记报损 SHRINKAGE）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes/ST.../adjust \
  -H 'Content-Type: application/json' -d '{"approve":true,"remark":"监控未见异常，按盘亏处理"}'
```

### 5.1) 盘点差异调查（酸奶系统 10 瓶、实盘 6 瓶，4 瓶差额查清）

盘点出现盘亏后，**先不要直接调整**，而是从盘亏行开立差异调查单。开立即冻结该批次的补货结算与供应商对账（其他商品照常结算），并自动装配时间窗证据。

```bash
# 1) 盘点录入实盘（系统 10、实盘 6，盘亏 4）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes \
  -H 'Content-Type: application/json' -d '{"cabinetNo":"CAB-001"}'
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/stocktakes/ST.../count \
  -H 'Content-Type: application/json' \
  -d '{"counts":[{"channelCode":"A02","batchNo":"B-MILK-1001","countedQty":6}]}'

# 2) 从盘亏行开立差异调查单（自动冻结 B-MILK-1001 批次结算/对账，预装配证据）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/discrepancies/open \
  -H 'Content-Type: application/json' \
  -d '{"stocktakeNo":"ST...","channelCode":"A02","batchNo":"B-MILK-1001"}'
# 返回 discrepancyNo，例如 DC202610060001；status=INVESTIGATING，shortageQty=4

# 3) 证据回放：补货前后库存、补货照片、销售订单、开门记录与多源识别（含视频片段）、
#    报损记录、温控曲线，一次返回
curl -s -u operator:cabinet123 http://localhost:8080/api/discrepancies/DC.../evidence
```

**按发生时间和证据拆账**——同一时间段有多种可能时，不把 4 瓶全记到补货员名下。下面把 4 瓶拆成：补货少放 1、识别漏单 1、温控损耗 1，剩 1 瓶证据不足自动挂起：

```bash
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/discrepancies/DC.../allocate \
  -H 'Content-Type: application/json' -d '{
    "conclusion":"三种成因各1瓶，余1瓶待查",
    "lines":[
      {"cause":"RESTOCK_SHORT","qty":1,"refType":"RESTOCK_ORDER","refNo":"RS...","evidenceNote":"上架照片只能数出对应数量"},
      {"cause":"RECOGNITION_MISS","qty":1,"refType":"DOOR_SESSION","refNo":"S-xxxx","evidenceNote":"开门有拿起视频但无订单"},
      {"cause":"TEMP_ABUSE","qty":1,"evidenceNote":"温控曲线越限变质报废"}
    ]}'
# 数量之和 3 ≤ 盘亏 4 → status=PENDING_REVIEW，pendingQty=1；
# 仅对已确认 3 瓶订正库存/报损，挂起 1 瓶不动且批次仍冻结。
```

成因取值与责任分流：

| cause | 含义 | 默认责任 | 库存/报损 | 下游流程 |
| --- | --- | --- | --- | --- |
| `RESTOCK_SHORT` | 补货时少放 | 补货员 | 订正库存，不记供方报损 | 补货员结算 + 每件扣 2 分 |
| `SALE_NOT_DEDUCTED` | 销售没扣减库存 | 平台 | 订正库存（ADJUST_MINUS） | 补扣款另行处理 |
| `LOSS_MISSED` | 报损漏记 | 运营（可传 `liability=SUPPLIER` 记供方） | 出库并补登报损 | 供方账单 / 补货员结算 |
| `RECOGNITION_MISS` | 识别漏单 | 平台 | 出库记 PLATFORM 报损 | 识别算法样本回流 |
| `TEMP_ABUSE` | 冷藏温度异常损耗 | 运营 | 即时隔离/报废出库 | **食品安全复核先行处置（FOLLOW_UP 立即 DONE），不等普通追责** |

```bash
# 查看拆账条目 / 下游流程 / 每次数量责任变化流水
curl -s -u operator:cabinet123 http://localhost:8080/api/discrepancies/DC.../causes
curl -s -u operator:cabinet123 http://localhost:8080/api/discrepancies/DC.../follow-ups
curl -s -u operator:cabinet123 http://localhost:8080/api/discrepancies/DC.../traces

# 4) 补传照片、跨班次补货、已结算订单都接回【原差异单】
curl -s -u replenisher:cabinet123 -X POST http://localhost:8080/api/discrepancies/DC.../photos \
  -H 'Content-Type: application/json' -d '{"photoUrl":"https://example.io/photos/proof.jpg","note":"到货整箱照"}'
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/discrepancies/DC.../cross-shift-restock \
  -H 'Content-Type: application/json' -d '{"restockOrderNo":"RS...","note":"夜班补放2瓶"}'
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/discrepancies/DC.../settled-order \
  -H 'Content-Type: application/json' -d '{"orderNo":"OD...","note":"核对是否漏扣"}'

# 5) 重复盘点接回（实盘变化时原拆账自动冲销回退、维持冻结，等待改判）
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/discrepancies/DC.../recount \
  -H 'Content-Type: application/json' -d '{"countedQty":8,"note":"复点"}'

# 6) 新证据到位后改判：冲销旧条目（回补库存、红冲报损、回退补货员评分），按新数量重建
curl -s -u operator:cabinet123 -X POST http://localhost:8080/api/discrepancies/DC.../rejudge \
  -H 'Content-Type: application/json' -d '{
    "conclusion":"照片佐证实放8瓶，2瓶为识别漏单",
    "lines":[
      {"cause":"RESTOCK_SHORT","qty":2},
      {"cause":"RECOGNITION_MISS","qty":2}
    ]}'
# pendingQty=0 → status=RE_JUDGED，批次解冻；traces 里可看每一轮数量与责任变化。
```

差异冻结期间，财务生成供应商结算单时会**跳过被冻结批次**（结算单 `remark` 列出跳过批次），同供应商其他批次照常结算；改判红冲的报损（`loss_record.reversed=true`）不再计入供应商账单。

### 6) 投诉 → 证据回放 → 退款

```bash
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints \
  -H 'Content-Type: application/json' \
  -d '{"sessionNo":"S-DEMO-1","category":"误扣款","description":"我拿起又放回了仍被扣费"}'

curl -s -u service:cabinet123 http://localhost:8080/api/complaints/CP.../evidence
# 一次返回：开门记录、识别事件（含视频片段）、订单明细、批次台账（库存变化）、温控记录

# 判责整单退款、商品未收回（产生 REFUND_NO_RETURN 报损）
curl -s -u service:cabinet123 -X POST http://localhost:8080/api/complaints/CP.../resolve \
  -H 'Content-Type: application/json' \
  -d '{"grantRefund":true,"goodsReturned":false,"resolution":"视频显示已放回，整单退款"}'
```

### 7) 运营统计 / 批次追溯 / 供应商结算

```bash
curl -s -u operator:cabinet123 "http://localhost:8080/api/analytics/points?from=2026-10-01&to=2026-10-31"
curl -s -u operator:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/trace
curl -s -u operator:cabinet123 http://localhost:8080/api/analytics/batches/B-MILK-1001/losses

# 财务按周期生成结算单（supplierId 见 GET /api/suppliers）
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/generate \
  -H 'Content-Type: application/json' \
  -d '{"supplierId":1,"start":"2026-10-01","end":"2026-10-31"}'
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/STL.../confirm
curl -s -u finance:cabinet123 -X POST http://localhost:8080/api/finance/settlements/STL.../pay
```

### 8) Kafka 消息链路验证

设备事件默认投递 `cabinet.device.event`（3 分区，key=cabinetNo 保证柜机内有序）；
结算、退款、下架、报损、温控锁定、结算确认会发布到 `cabinet.biz.event`；处理失败重试 2 次后进入 `cabinet.device.event.DLT`。

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic cabinet.biz.event --from-beginning
```

## 六、自动化测试

```bash
mvn test
```

- `RecognitionFusionTest`：拿起放回抵消、多源一致融合、多源冲突降级、称重交叉校验；
- `BusinessFlowIntegrationTest`（H2）：补货上架/放错货道/过期禁上架、关门结算 FEFO、拿起放回取消、存疑复核、温控锁定隔离、临期下架报损、盘点盘亏、投诉退款（收回/未收回）、供应商结算口径。
- `DiscrepancyInvestigationTest`（H2）：4 瓶差额按证据拆成补货少放/识别漏单/温控损耗且挂起无法确认部分、只冻结相关批次其他批次照常结算、温控食品安全先行、补货员评分/供应商账单/算法样本分流、改判红冲报损并回退评分、重复盘点回退、跨班次补货与已结算订单接回留痕。

## 七、主要数据模型

```
point_location 1—N cabinet 1—N channel N—1 product(计划品类)
restock_order 1—N restock_item（批次/保质期/数量/货道/温区/照片/告警）
product_batch 1—N channel_stock（货道批次库存，含隔离量）
product_batch 1—N batch_ledger（补货/售卖/下架/报损/盘点/退款/结算 逐笔）
door_session 1—N recognition_event（重量/视觉/RFID，含视频片段）
door_session 1—1 order_record 1—N order_item（精确到批次）
removal_order 1—N removal_item；执行后按去向写 loss_record / 回退供应商
stocktake_order 1—N stocktake_item（系统数/实盘数/差异/调整标记）
discrepancy_order 1—N discrepancy_cause_item（按时间证据拆账，PENDING 挂起，REVERSED 改判留痕）
discrepancy_order 1—N discrepancy_follow_up（补货员结算/供应商账单/算法样本/食品安全分流）
discrepancy_order 1—N discrepancy_evidence（补货前后库存/照片/订单/开门/报损/温控）
discrepancy_order 1—N discrepancy_trace（补证/复点/跨班次/已结算订单接回、冻结/解冻、改判流水）
product_batch 增 settlement_frozen（差异未查清只冻结相关批次）；loss_record 增 reversed（改判红冲）
complaint N—1 door_session；settlement_batch 1—N settlement_item（批次级对账）
temp_log / alert 支撑温控与运营处置
```
