package com.campus.food.web;

import com.campus.food.domain.SupplierAssessment;
import com.campus.food.domain.enums.AssessmentActionType;
import com.campus.food.domain.enums.AssessmentStatus;
import com.campus.food.service.AssessmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 供应商连续异常考核。
 * 系统在三周内多次出现票证缺失/蔬菜腐烂时自动生成考核事件；
 * 采购负责人可要求整改、暂停供货或降低下月采购比例；
 * 供应商提交整改材料后进入重点验收，连续通过后由采购负责人恢复正常供货。
 */
@Tag(name = "供应商连续异常考核")
@RestController
@RequestMapping("/api/assessments")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    public record DecideReq(@NotNull AssessmentActionType actionType,
                            BigDecimal adjustedRatio, String note) {
    }

    public record RectificationReq(@NotBlank String material, String attachments) {
    }

    public record ResumeReq(String note) {
    }

    @Operation(summary = "考核事件列表（可按供应商/状态过滤）")
    @GetMapping
    public List<SupplierAssessment> list(@RequestParam(required = false) Long supplierId,
                                         @RequestParam(required = false) AssessmentStatus status) {
        return assessmentService.list(supplierId, status);
    }

    @Operation(summary = "考核事件详情（含异常批次证据、验收照片、退货金额、菜单影响、重点验收记录）")
    @GetMapping("/{id}")
    public AssessmentService.AssessmentDetail detail(@PathVariable Long id) {
        return assessmentService.detail(id);
    }

    @Operation(summary = "采购负责人处置：RECTIFY 要求整改 / SUSPEND 暂停供货 / REDUCE_RATIO 降低下月采购比例")
    @PostMapping("/{id}/decide")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public SupplierAssessment decide(@PathVariable Long id, @Valid @RequestBody DecideReq req) {
        return assessmentService.decide(id, req.actionType(), req.adjustedRatio(), req.note());
    }

    @Operation(summary = "供应商提交整改材料（提交后后续送货进入重点/更严格验收）")
    @PostMapping("/{id}/rectification")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public SupplierAssessment submitRectification(@PathVariable Long id,
                                                  @Valid @RequestBody RectificationReq req) {
        return assessmentService.submitRectification(id, req.material(), req.attachments());
    }

    @Operation(summary = "连续通过重点验收后恢复正常供货比例并关闭考核")
    @PostMapping("/{id}/resume")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public SupplierAssessment resume(@PathVariable Long id, @RequestBody(required = false) ResumeReq req) {
        return assessmentService.resume(id, req == null ? null : req.note());
    }
}
