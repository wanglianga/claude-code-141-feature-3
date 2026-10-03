package com.campus.food.web;

import com.campus.food.domain.AssessmentEvent;
import com.campus.food.domain.RectificationMaterial;
import com.campus.food.domain.enums.AssessmentStatus;
import com.campus.food.service.AssessmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 供应商连续异常考核：自动触发（三周内多次票证缺失/蔬菜腐烂）→ 采购处置
 * （整改/暂停/降低下月采购比例）→ 供应商提交整改材料 → 重点验收观察 → 连续通过恢复供货。
 */
@Tag(name = "供应商连续异常考核")
@RestController
@RequestMapping("/api/assessments")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;

    public record RectifyReq(@NotNull LocalDate deadline, String note) {
    }

    public record SuspendReq(String note) {
    }

    public record ReduceRatioReq(@NotNull BigDecimal ratio, String note) {
    }

    public record CloseReq(String note) {
    }

    public record MaterialReq(@NotBlank String title, @NotBlank String content, String attachments) {
    }

    public record ConfirmMaterialReq(Integer requiredPasses, String reviewNote) {
    }

    @Operation(summary = "考核事件列表（供应商账号仅可见本供应商；可按供应商/状态过滤）")
    @GetMapping
    public List<AssessmentEvent> list(Authentication auth,
                                      @RequestParam(required = false) Long supplierId,
                                      @RequestParam(required = false) AssessmentStatus status) {
        return assessmentService.listForUser(auth.getName(), supplierId, status);
    }

    @Operation(summary = "考核事件详情（汇总异常批次/验收照片/退货金额/菜单影响/整改材料）")
    @GetMapping("/{id}")
    public AssessmentService.EventDetail detail(Authentication auth, @PathVariable Long id) {
        return assessmentService.detailForUser(id, auth.getName());
    }

    @Operation(summary = "采购负责人要求限期整改")
    @PostMapping("/{id}/rectify")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public AssessmentEvent rectify(Authentication auth, @PathVariable Long id,
                                   @Valid @RequestBody RectifyReq req) {
        return assessmentService.requireRectification(id, auth.getName(), req.deadline(), req.note());
    }

    @Operation(summary = "采购负责人暂停供货")
    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public AssessmentEvent suspend(Authentication auth, @PathVariable Long id,
                                   @RequestBody(required = false) SuspendReq req) {
        return assessmentService.suspend(id, auth.getName(), req == null ? null : req.note());
    }

    @Operation(summary = "采购负责人降低下月采购比例（0~1）")
    @PostMapping("/{id}/reduce-ratio")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public AssessmentEvent reduceRatio(Authentication auth, @PathVariable Long id,
                                       @Valid @RequestBody ReduceRatioReq req) {
        return assessmentService.reduceRatio(id, auth.getName(), req.ratio(), req.note());
    }

    @Operation(summary = "采购负责人关闭考核（不进入整改流程）")
    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public AssessmentEvent close(Authentication auth, @PathVariable Long id,
                                 @RequestBody(required = false) CloseReq req) {
        return assessmentService.close(id, auth.getName(), req == null ? null : req.note());
    }

    @Operation(summary = "供应商提交整改材料（整改期内或暂停后放行前）")
    @PostMapping("/{id}/materials")
    @PreAuthorize("hasRole('SUPPLIER')")
    public RectificationMaterial submitMaterial(Authentication auth, @PathVariable Long id,
                                                @Valid @RequestBody MaterialReq req) {
        return assessmentService.submitMaterial(id, auth.getName(),
                req.title(), req.content(), req.attachments());
    }

    @Operation(summary = "采购负责人确认整改材料：后续送货进入重点验收（暂停状态同步恢复供货资格）")
    @PostMapping("/materials/{materialId}/confirm")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public AssessmentEvent confirmMaterial(Authentication auth, @PathVariable Long materialId,
                                           @RequestBody(required = false) ConfirmMaterialReq req) {
        return assessmentService.confirmMaterial(materialId, auth.getName(),
                req == null ? null : req.requiredPasses(),
                req == null ? null : req.reviewNote());
    }
}
