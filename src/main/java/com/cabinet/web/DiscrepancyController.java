package com.cabinet.web;

import com.cabinet.domain.DiscrepancyFollowUp;
import com.cabinet.domain.DiscrepancyOrder;
import com.cabinet.domain.enums.DiscrepancyStatus;
import com.cabinet.domain.enums.FollowUpType;
import com.cabinet.dto.DiscrepancyEvidenceView;
import com.cabinet.service.DiscrepancyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;

/**
 * 盘点差异调查：
 * 开立（冻结相关批次结算/对账）→ 证据回放 → 按时间与证据拆账（挂起无法确认部分）
 * → 责任确认分流（补货员/供应商/算法样本/食品安全，温控先行处置）
 * → 补传照片/重复盘点/跨班次补货/已结算订单接回 → 改判。
 */
@Tag(name = "盘点差异调查",
        description = "差异单开立冻结、证据回放（补货前后库存/照片/订单/开门/报损/温控）、按时间与证据拆账归责、食品安全先行、补传/重复盘点/跨班次/已结算订单接回、改判留痕")
@RestController
@RequestMapping("/api/discrepancies")
@RequiredArgsConstructor
public class DiscrepancyController {

    private final DiscrepancyService discrepancyService;

    public record OpenRequest(String stocktakeNo, String channelCode, String batchNo) {}

    public record AllocateRequest(List<DiscrepancyService.AllocateLine> lines, String conclusion) {}

    public record PhotoRequest(String photoUrl, String note) {}

    public record RecountRequest(Integer countedQty, String note) {}

    public record CrossShiftRequest(String restockOrderNo, String note) {}

    public record SettledOrderRequest(String orderNo, String note) {}

    public record FollowUpRequest(FollowUpType type, String refNo, String detail) {}

    @Operation(summary = "从盘点盘亏行发起差异调查（自动冻结该批次补货结算与供应商对账、预装配证据）")
    @PostMapping("/open")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public DiscrepancyOrder open(@RequestBody OpenRequest req, Principal principal) {
        return discrepancyService.openFromStocktake(req.stocktakeNo(), req.channelCode(),
                req.batchNo(), principal.getName());
    }

    @Operation(summary = "差异单列表（可按柜机/状态过滤）")
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','FINANCE','CUSTOMER_SERVICE')")
    public List<DiscrepancyOrder> list(@RequestParam(required = false) String cabinetNo,
                                       @RequestParam(required = false) DiscrepancyStatus status,
                                       @RequestParam(required = false) DiscrepancyStatus[] statuses) {
        List<DiscrepancyStatus> filter = statuses != null ? Arrays.asList(statuses)
                : (status != null ? List.of(status) : null);
        return discrepancyService.list(cabinetNo, filter);
    }

    @Operation(summary = "差异单详情")
    @GetMapping("/{discrepancyNo}")
    @PreAuthorize("isAuthenticated()")
    public DiscrepancyOrder get(@PathVariable String discrepancyNo) {
        return discrepancyService.get(discrepancyNo);
    }

    @Operation(summary = "证据回放：补货前后库存/补货照片/销售订单/开门识别/报损/温控曲线 + 拆账/流转/流水")
    @GetMapping("/{discrepancyNo}/evidence")
    @PreAuthorize("isAuthenticated()")
    public DiscrepancyEvidenceView evidence(@PathVariable String discrepancyNo) {
        return discrepancyService.evidence(discrepancyNo);
    }

    @Operation(summary = "按发生时间与证据拆账归责（数量之和≤盘亏，剩余自动挂起复核）；温控条目即时安全处置")
    @PostMapping("/{discrepancyNo}/allocate")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public DiscrepancyOrder allocate(@PathVariable String discrepancyNo,
                                     @RequestBody AllocateRequest req, Principal principal) {
        return discrepancyService.allocate(discrepancyNo, principal.getName(),
                req.lines(), req.conclusion());
    }

    @Operation(summary = "改判：补传/重复盘点/跨班次/已结算订单接回后重新拆账，冲销旧条目并全程留痕")
    @PostMapping("/{discrepancyNo}/rejudge")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public DiscrepancyOrder rejudge(@PathVariable String discrepancyNo,
                                    @RequestBody AllocateRequest req, Principal principal) {
        return discrepancyService.rejudge(discrepancyNo, principal.getName(),
                req.lines(), req.conclusion());
    }

    @Operation(summary = "补传照片，接回原差异单")
    @PostMapping("/{discrepancyNo}/photos")
    @PreAuthorize("hasAnyRole('REPLENISHER','OPERATOR','ADMIN')")
    public DiscrepancyOrder photo(@PathVariable String discrepancyNo,
                                  @RequestBody PhotoRequest req, Principal principal) {
        return discrepancyService.attachPhoto(discrepancyNo, principal.getName(),
                req.photoUrl(), req.note());
    }

    @Operation(summary = "重复盘点接回（实盘变化时原拆账回退、维持冻结，等待改判）")
    @PostMapping("/{discrepancyNo}/recount")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public DiscrepancyOrder recount(@PathVariable String discrepancyNo,
                                    @RequestBody RecountRequest req, Principal principal) {
        return discrepancyService.attachRecount(discrepancyNo, principal.getName(),
                req.countedQty(), req.note());
    }

    @Operation(summary = "跨班次补货单接回原差异单")
    @PostMapping("/{discrepancyNo}/cross-shift-restock")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public DiscrepancyOrder crossShift(@PathVariable String discrepancyNo,
                                       @RequestBody CrossShiftRequest req, Principal principal) {
        return discrepancyService.attachCrossShiftRestock(discrepancyNo, principal.getName(),
                req.restockOrderNo(), req.note());
    }

    @Operation(summary = "已结算订单接回原差异单核对（漏扣/错扣）")
    @PostMapping("/{discrepancyNo}/settled-order")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','FINANCE','CUSTOMER_SERVICE')")
    public DiscrepancyOrder settledOrder(@PathVariable String discrepancyNo,
                                         @RequestBody SettledOrderRequest req, Principal principal) {
        return discrepancyService.attachSettledOrder(discrepancyNo, principal.getName(),
                req.orderNo(), req.note());
    }

    @Operation(summary = "查询拆账条目")
    @GetMapping("/{discrepancyNo}/causes")
    @PreAuthorize("isAuthenticated()")
    public Object causes(@PathVariable String discrepancyNo) {
        return discrepancyService.causes(discrepancyNo);
    }

    @Operation(summary = "查询下游流程（补货员结算/供应商账单/算法样本/食品安全）")
    @GetMapping("/{discrepancyNo}/follow-ups")
    @PreAuthorize("isAuthenticated()")
    public List<DiscrepancyFollowUp> followUps(@PathVariable String discrepancyNo) {
        return discrepancyService.followUps(discrepancyNo);
    }

    @Operation(summary = "标记某类下游流程已办结（如供应商账单已扣、算法样本已入库）")
    @PostMapping("/{discrepancyNo}/follow-ups/complete")
    @PreAuthorize("hasAnyRole('OPERATOR','FINANCE','ADMIN')")
    public DiscrepancyFollowUp completeFollowUp(@PathVariable String discrepancyNo,
                                                @RequestBody FollowUpRequest req,
                                                Principal principal) {
        return discrepancyService.completeFollowUp(discrepancyNo, principal.getName(),
                req.type(), req.refNo(), req.detail());
    }

    @Operation(summary = "差异单接回/改判流水（每次数量与责任变化）")
    @GetMapping("/{discrepancyNo}/traces")
    @PreAuthorize("isAuthenticated()")
    public Object traces(@PathVariable String discrepancyNo) {
        return discrepancyService.traces(discrepancyNo);
    }
}
