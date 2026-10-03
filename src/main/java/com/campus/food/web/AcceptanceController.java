package com.campus.food.web;

import com.campus.food.domain.AcceptanceItem;
import com.campus.food.domain.AcceptanceRecord;
import com.campus.food.service.AcceptanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "验收")
@RestController
@RequestMapping("/api/acceptances")
@RequiredArgsConstructor
public class AcceptanceController {

    private final AcceptanceService acceptanceService;

    public record AcceptanceItemReq(@NotNull Long deliveryItemId,
                                    BigDecimal acceptedQty, BigDecimal rejectedQty,
                                    BigDecimal downgradedQty, BigDecimal rotRatio,
                                    String rejectReason, List<String> photos) {
    }

    public record CreateAcceptanceReq(@NotNull Long batchId, BigDecimal measuredTemp,
                                      Boolean temperatureOk, Boolean appearanceOk,
                                      String samplingResult, String notes,
                                      List<String> photos,
                                      @NotEmpty List<AcceptanceItemReq> items) {
    }

    public record DowngradeApprovalReq(@NotNull Boolean approve, BigDecimal discountRate) {
    }

    @Operation(summary = "创建验收记录（合格入库/拒收退货/降级待审批；自动识别少送、腐烂超标、票证缺失、已备餐无法拒收；整改观察期自动执行重点验收）")
    @PostMapping
    @PreAuthorize("hasRole('INSPECTOR')")
    public AcceptanceRecord create(Authentication auth, @Valid @RequestBody CreateAcceptanceReq req) {
        List<AcceptanceService.AcceptanceItemInput> items = req.items().stream()
                .map(i -> new AcceptanceService.AcceptanceItemInput(i.deliveryItemId(), i.acceptedQty(),
                        i.rejectedQty(), i.downgradedQty(), i.rotRatio(), i.rejectReason(), i.photos()))
                .toList();
        return acceptanceService.create(auth.getName(), req.batchId(), req.measuredTemp(),
                req.temperatureOk(), req.appearanceOk(), req.samplingResult(), req.notes(),
                req.photos(), items);
    }

    @Operation(summary = "验收记录详情（含明细）")
    @GetMapping("/{id}")
    public AcceptanceService.RecordDetail detail(@PathVariable Long id) {
        return acceptanceService.detail(id);
    }

    @Operation(summary = "降级使用审批（批准则折价入库，驳回则转退货）")
    @PostMapping("/items/{itemId}/downgrade-approval")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public AcceptanceItem approveDowngrade(@PathVariable Long itemId,
                                           @Valid @RequestBody DowngradeApprovalReq req) {
        return acceptanceService.approveDowngrade(itemId, req.approve(), req.discountRate());
    }
}
