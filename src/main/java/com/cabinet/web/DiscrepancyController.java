package com.cabinet.web;

import com.cabinet.domain.*;
import com.cabinet.domain.enums.Disposition;
import com.cabinet.domain.enums.FollowUpType;
import com.cabinet.domain.enums.FoodSafetyStatus;
import com.cabinet.dto.DiscrepancyEvidenceView;
import com.cabinet.repo.DiscrepancyOrderRepository;
import com.cabinet.service.DiscrepancyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 盘点差异核查：立案冻结、证据回放、按时间/证据拆分定责、挂起复核、
 * 食品安全先行处置、补传/重复盘点/跨班次/已结算订单接回、改判、补货员结算与算法样本。
 */
@Tag(name = "盘点差异核查",
        description = "差异单关联补货前后库存/照片、销售订单、开门记录、报损、温控曲线；"
                + "按时间与证据拆分定责，未查清仅冻结相关批次结算；温度异常先行食安处置；全程改判留痕")
@RestController
@RequestMapping("/api/discrepancies")
@RequiredArgsConstructor
public class DiscrepancyController {

    private final DiscrepancyService discrepancyService;
    private final DiscrepancyOrderRepository orderRepository;

    public record OpenFromStocktakeRequest(String stocktakeNo, String channelCode, String batchNo) {}
    public record OpenRequest(String cabinetNo, String channelCode, String batchNo,
                              Integer systemQty, Integer countedQty, String remark) {}
    public record AllocateRequest(List<DiscrepancyService.AllocateLine> lines) {}
    public record FoodSafetyRequest(Integer qty, FoodSafetyStatus result, Disposition disposition,
                                    String remark) {}
    public record FollowUpRequest(FollowUpType type, String refNo, String photoUrls,
                                  Integer qtyChange, String remark) {}
    public record RecountRequest(Integer countedQty, String remark) {}
    public record RejudgeRequest(DiscrepancyService.AllocateLine line, String remark) {}
    public record SampleReviewRequest(String note) {}

    @Operation(summary = "从盘点单差异行立案核查（自动冻结相关批次结算/对账）")
    @PostMapping("/from-stocktake")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public DiscrepancyOrder openFromStocktake(@RequestBody OpenFromStocktakeRequest req,
                                              Principal principal) {
        return discrepancyService.openFromStocktake(req.stocktakeNo(), req.channelCode(),
                req.batchNo(), principal.getName());
    }

    @Operation(summary = "直接立案核查（盘点单之外，按柜机/批次/系统数/实盘数）")
    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public DiscrepancyOrder open(@RequestBody OpenRequest req, Principal principal) {
        return discrepancyService.open(req.cabinetNo(), req.channelCode(), req.batchNo(),
                req.systemQty(), req.countedQty(), principal.getName(), req.remark());
    }

    @Operation(summary = "差异核查单列表（可按柜机筛选）")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<DiscrepancyOrder> list(@RequestParam(required = false) Long cabinetId) {
        return cabinetId == null ? orderRepository.findAll()
                : orderRepository.findByCabinetIdOrderByCreatedAtDesc(cabinetId);
    }

    @Operation(summary = "差异单详情")
    @GetMapping("/{discrepancyNo}")
    @PreAuthorize("isAuthenticated()")
    public DiscrepancyOrder detail(@PathVariable String discrepancyNo) {
        return discrepancyService.must(discrepancyNo);
    }

    @Operation(summary = "证据回放：补货前后库存/照片、销售订单、开门记录、识别事件、报损、温控曲线、候选原因")
    @GetMapping("/{discrepancyNo}/evidence")
    @PreAuthorize("isAuthenticated()")
    public DiscrepancyEvidenceView evidence(@PathVariable String discrepancyNo) {
        return discrepancyService.evidence(discrepancyNo);
    }

    @Operation(summary = "差异分摊行（按发生时间排序的每笔数量与责任）")
    @GetMapping("/{discrepancyNo}/allocations")
    @PreAuthorize("isAuthenticated()")
    public List<DiscrepancyAllocation> allocations(@PathVariable String discrepancyNo) {
        return discrepancyService.allocations(discrepancyNo);
    }

    @Operation(summary = "提交拆分定责：按发生时间+证据逐瓶拆分；UNCONFIRMED 行挂起复核，其余立即记账并分流")
    @PostMapping("/{discrepancyNo}/allocations")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public List<DiscrepancyAllocation> allocate(@PathVariable String discrepancyNo,
                                                @RequestBody AllocateRequest req, Principal principal) {
        return discrepancyService.allocate(discrepancyNo, req.lines(), principal.getName());
    }

    @Operation(summary = "食品安全先行处置：温控异常商品隔离后报废/复检合格/召回（可在定责完成前执行）")
    @PostMapping("/{discrepancyNo}/food-safety")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public FoodSafetyReview foodSafety(@PathVariable String discrepancyNo,
                                       @RequestBody FoodSafetyRequest req, Principal principal) {
        return discrepancyService.handleFoodSafety(discrepancyNo, req.qty(), req.result(),
                req.disposition(), principal.getName(), req.remark());
    }

    @Operation(summary = "食品安全复核记录")
    @GetMapping("/{discrepancyNo}/food-safety")
    @PreAuthorize("isAuthenticated()")
    public List<FoodSafetyReview> foodSafetyList(@PathVariable String discrepancyNo) {
        return discrepancyService.foodSafetyReviews(discrepancyNo);
    }

    @Operation(summary = "结单（全部差异已定责且无挂起时生效，解冻批次结算）")
    @PostMapping("/{discrepancyNo}/resolve")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public DiscrepancyOrder resolve(@PathVariable String discrepancyNo, Principal principal) {
        return discrepancyService.resolve(discrepancyNo, principal.getName());
    }

    @Operation(summary = "改判：作废原分摊行并按新原因/责任重新定责（自动冲销库存/台账/报损/罚则，结单自动重开）")
    @PostMapping("/{discrepancyNo}/allocations/{allocationId}/rejudge")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public DiscrepancyAllocation rejudge(@PathVariable String discrepancyNo,
                                         @PathVariable Long allocationId,
                                         @RequestBody RejudgeRequest req, Principal principal) {
        return discrepancyService.rejudge(discrepancyNo, allocationId, req.line(), principal.getName());
    }

    @Operation(summary = "接回事后证据/业务：补传照片、跨班次补货、已结算订单等挂回原差异单")
    @PostMapping("/{discrepancyNo}/follow-ups")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public DiscrepancyFollowUp attachFollowUp(@PathVariable String discrepancyNo,
                                              @RequestBody FollowUpRequest req, Principal principal) {
        return discrepancyService.attachFollowUp(discrepancyNo, req.type(), req.refNo(),
                req.photoUrls(), req.qtyChange(), principal.getName(), req.remark());
    }

    @Operation(summary = "接回记录列表（看清每一次数量与责任变化）")
    @GetMapping("/{discrepancyNo}/follow-ups")
    @PreAuthorize("isAuthenticated()")
    public List<DiscrepancyFollowUp> followUps(@PathVariable String discrepancyNo) {
        return discrepancyService.followUps(discrepancyNo);
    }

    @Operation(summary = "重复盘点：以新实盘数量接回原差异单（差异数量变化时自动重开核查）")
    @PostMapping("/{discrepancyNo}/recount")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','REPLENISHER')")
    public DiscrepancyOrder recount(@PathVariable String discrepancyNo,
                                    @RequestBody RecountRequest req, Principal principal) {
        return discrepancyService.recount(discrepancyNo, req.countedQty(),
                principal.getName(), req.remark());
    }

    @Operation(summary = "补货员待结算差异扣罚")
    @GetMapping("/replenisher-penalties")
    @PreAuthorize("hasAnyRole('FINANCE','OPERATOR','ADMIN')")
    public List<ReplenisherPenalty> penalties(@RequestParam(required = false) Long replenisherId) {
        return discrepancyService.pendingPenalties(replenisherId);
    }

    @Operation(summary = "补货员差异责任结算扣款（评分已定责时计入，此处落扣款）")
    @PostMapping("/replenisher-penalties/{replenisherId}/settle")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public Map<String, Object> settleReplenisher(@PathVariable Long replenisherId, Principal principal) {
        return discrepancyService.settleReplenisher(replenisherId, principal.getName());
    }

    @Operation(summary = "待算法团队复核的识别漏单/未扣减样本")
    @GetMapping("/recognition-samples")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public List<RecognitionSample> samples() {
        return discrepancyService.pendingSamples();
    }

    @Operation(summary = "算法样本复核（采纳回流/误判标注）")
    @PostMapping("/recognition-samples/{sampleId}/review")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public RecognitionSample reviewSample(@PathVariable Long sampleId, @RequestBody SampleReviewRequest req) {
        return discrepancyService.reviewSample(sampleId, req.note());
    }
}
