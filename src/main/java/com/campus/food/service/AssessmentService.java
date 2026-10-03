package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 供应商连续异常考核服务。
 * 同一供应商三周内多次出现票证缺失 / 蔬菜腐烂时自动生成考核事件，
 * 汇总异常批次、验收照片、退货金额与对菜单影响；
 * 支持采购负责人要求整改 / 暂停供货 / 降低下月采购比例；
 * 供应商提交整改材料后后续送货进入重点（更严格）验收，连续通过后由采购负责人恢复正常供货。
 */
@Service
@RequiredArgsConstructor
public class AssessmentService {

    /** 考核处置后仍未关闭、视为"在考核中"的状态 */
    private static final List<AssessmentStatus> ACTIVE_STATUSES = List.of(
            AssessmentStatus.OPEN, AssessmentStatus.RECTIFYING, AssessmentStatus.UNDER_REVIEW,
            AssessmentStatus.SUSPENDED, AssessmentStatus.RATIO_REDUCED);

    private final SupplierAssessmentRepo assessmentRepo;
    private final AssessmentBatchEvidenceRepo evidenceRepo;
    private final AssessmentAcceptanceCheckRepo checkRepo;
    private final SupplierRepo supplierRepo;
    private final AnomalyRecordRepo anomalyRepo;
    private final DeliveryBatchRepo batchRepo;
    private final DeliveryItemRepo deliveryItemRepo;
    private final AcceptanceRecordRepo recordRepo;
    private final ReturnOrderRepo returnRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;

    // ============================ 自动触发 ============================

    /**
     * 一次验收登记异常后评估是否触发连续异常考核。
     * 已存在未关闭考核事件的供应商不再重复生成。
     */
    @Transactional
    public Optional<SupplierAssessment> evaluateAfterAnomaly(Long supplierId) {
        if (assessmentRepo.existsBySupplierIdAndStatusIn(supplierId, ACTIVE_STATUSES)) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusDays(AssessmentRules.WINDOW_DAYS);
        // 新一轮观察窗口从上一次考核事件（含已关闭）之后起算，避免窗口内旧异常被重复计入
        LocalDateTime lastWindowEnd = assessmentRepo.findFirstBySupplierIdOrderByCreatedAtDesc(supplierId)
                .map(SupplierAssessment::getWindowEnd).orElse(null);
        if (lastWindowEnd != null && lastWindowEnd.isAfter(windowStart)) {
            windowStart = lastWindowEnd;
        }
        List<AnomalyRecord> windowAnomalies =
                anomalyRepo.findBySupplierIdAndDetectedAtBetween(supplierId, windowStart, now);

        int missingDoc = 0;
        int vegRot = 0;
        for (AnomalyRecord a : windowAnomalies) {
            if (a.getType() == AnomalyType.MISSING_DOCUMENTS) {
                missingDoc++;
            } else if (a.getType() == AnomalyType.HIGH_ROT_RATIO
                    && a.getCategory() == IngredientCategory.VEGETABLE) {
                vegRot++;
            }
        }
        if (!AssessmentRules.shouldTrigger(missingDoc, vegRot)) {
            return Optional.empty();
        }

        Supplier supplier = supplierRepo.findById(supplierId)
                .orElseThrow(() -> BusinessException.notFound("供应商不存在: " + supplierId));

        // 触发考核的观察异常（票证缺失 / 蔬菜腐烂），按批次归集
        List<AnomalyRecord> observed = windowAnomalies.stream()
                .filter(a -> AssessmentRules.isObserved(a.getType(), a.getCategory()))
                .toList();
        Map<Long, List<AnomalyRecord>> byBatch = observed.stream()
                .filter(a -> a.getBatch() != null)
                .collect(Collectors.groupingBy(a -> a.getBatch().getId()));

        BigDecimal returnTotal = BigDecimal.ZERO;
        Set<Long> impactedMealIds = new HashSet<>();
        LocalDate today = LocalDate.now();
        List<AssessmentBatchEvidence> evidences = new ArrayList<>();

        for (List<AnomalyRecord> batchAnomalies : byBatch.values()) {
            DeliveryBatch batch = batchAnomalies.get(0).getBatch();

            // 该批次在窗口内的全部异常类型（证据上下文）
            String types = windowAnomalies.stream()
                    .filter(a -> a.getBatch() != null && a.getBatch().getId().equals(batch.getId()))
                    .map(a -> a.getType().name()).distinct().sorted()
                    .collect(Collectors.joining(","));

            // 验收照片
            String photos = recordRepo.findByBatchId(batch.getId())
                    .map(AcceptanceRecord::getPhotoUrls).orElse(null);

            // 该批次退货金额
            BigDecimal batchReturn = returnRepo.findByAcceptanceItemRecordBatchId(batch.getId()).stream()
                    .map(ReturnOrder::getRefundAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            returnTotal = returnTotal.add(batchReturn);

            // 对菜单影响：该批次食材在未来 7 天餐次中的出现
            for (DeliveryItem di : deliveryItemRepo.findByBatchId(batch.getId())) {
                mealIngredientRepo.findMealsNeeding(di.getIngredient().getId(), today, today.plusDays(7),
                                List.of(MealStatus.SCHEDULED, MealStatus.PREPARED))
                        .forEach(mmi -> impactedMealIds.add(mmi.getMeal().getId()));
            }

            AssessmentBatchEvidence ev = new AssessmentBatchEvidence();
            ev.setBatch(batch);
            ev.setBatchNo(batch.getBatchNo());
            ev.setAnomalyTypes(types);
            ev.setPhotoUrls(photos);
            ev.setReturnAmount(batchReturn);
            evidences.add(ev);
        }

        SupplierAssessment a = new SupplierAssessment();
        a.setAssessmentNo(NoGen.next("AS"));
        a.setSupplier(supplier);
        a.setStatus(AssessmentStatus.OPEN);
        a.setWindowStart(windowStart);
        a.setWindowEnd(now);
        a.setMissingDocCount(missingDoc);
        a.setVegetableRotCount(vegRot);
        a.setAbnormalBatchCount(byBatch.size());
        a.setReturnAmount(returnTotal);
        a.setImpactedMealCount(impactedMealIds.size());
        a.setSummary(String.format(
                "近三周票证缺失 %d 次、蔬菜腐烂 %d 次，涉及异常批次 %d 个，退货金额 %.2f 元，受影响未来餐次 %d 个",
                missingDoc, vegRot, byBatch.size(), returnTotal, impactedMealIds.size()));
        assessmentRepo.save(a);
        for (AssessmentBatchEvidence ev : evidences) {
            ev.setAssessment(a);
            evidenceRepo.save(ev);
        }
        return Optional.of(a);
    }

    // ============================ 采购处置 ============================

    /** 采购负责人处置：要求整改 / 暂停供货 / 降低下月采购比例 */
    @Transactional
    public SupplierAssessment decide(Long id, AssessmentActionType action, BigDecimal adjustedRatio, String note) {
        SupplierAssessment a = get(id);
        if (a.getStatus() != AssessmentStatus.OPEN) {
            throw BusinessException.conflict("考核事件已处置，不能重复作出决定（当前状态: " + a.getStatus() + "）");
        }
        if (action == null) {
            throw BusinessException.badRequest("必须指定处置方式 actionType");
        }
        Supplier supplier = a.getSupplier();
        a.setActionType(action);
        a.setDecisionNote(note);
        a.setDecidedAt(LocalDateTime.now());

        switch (action) {
            case RECTIFY -> a.setStatus(AssessmentStatus.RECTIFYING);
            case SUSPEND -> {
                a.setStatus(AssessmentStatus.SUSPENDED);
                supplier.setSuspended(true);
                supplierRepo.save(supplier);
            }
            case REDUCE_RATIO -> {
                if (adjustedRatio == null
                        || adjustedRatio.signum() <= 0 || adjustedRatio.compareTo(BigDecimal.ONE) >= 0) {
                    throw BusinessException.badRequest("降低采购比例需提供 0~1 之间的 adjustedRatio（不含 0 和 1）");
                }
                a.setAdjustedRatio(adjustedRatio);
                a.setStatus(AssessmentStatus.RATIO_REDUCED);
                supplier.setPurchaseRatio(adjustedRatio);
                supplierRepo.save(supplier);
            }
        }
        return assessmentRepo.save(a);
    }

    /** 供应商提交整改材料，后续送货进入重点验收 */
    @Transactional
    public SupplierAssessment submitRectification(Long id, String material, String attachments) {
        SupplierAssessment a = get(id);
        AssessmentStatus s = a.getStatus();
        if (s != AssessmentStatus.RECTIFYING && s != AssessmentStatus.SUSPENDED
                && s != AssessmentStatus.RATIO_REDUCED) {
            throw BusinessException.conflict("当前状态(" + s + ")不允许提交整改材料");
        }
        if (material == null || material.isBlank()) {
            throw BusinessException.badRequest("必须填写整改材料说明");
        }
        a.setRectificationMaterial(material);
        a.setRectificationAttachments(attachments);
        a.setRectifiedAt(LocalDateTime.now());
        a.setStatus(AssessmentStatus.UNDER_REVIEW);
        if (a.getRequiredPasses() <= 0) {
            a.setRequiredPasses(AssessmentRules.DEFAULT_REQUIRED_PASSES);
        }
        a.setConsecutivePasses(0);
        a.setLastCheckPassed(null);

        // 提交整改材料后恢复送货资格，但每次送货须通过重点验收
        Supplier supplier = a.getSupplier();
        if (supplier.isSuspended()) {
            supplier.setSuspended(false);
            supplierRepo.save(supplier);
        }
        return assessmentRepo.save(a);
    }

    /**
     * 验收完成后回写重点验收结果（仅整改期 UNDER_REVIEW 的供应商）。
     * 一次送货整体合格且未再出现票证缺失 / 腐烂异常记为通过，否则计数清零。
     */
    @Transactional
    public Optional<AssessmentAcceptanceCheck> recordStrictAcceptance(
            AcceptanceRecord record, List<AnomalyRecord> anomaliesOfAcceptance) {
        DeliveryBatch batch = record.getBatch();
        Supplier supplier = batch.getOrder().getSupplier();
        Optional<SupplierAssessment> opt = assessmentRepo
                .findFirstBySupplierIdAndStatusInOrderByCreatedAtDesc(supplier.getId(),
                        List.of(AssessmentStatus.UNDER_REVIEW));
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        SupplierAssessment a = opt.get();

        boolean observedAnomaly = anomaliesOfAcceptance.stream()
                .anyMatch(x -> AssessmentRules.isObserved(x.getType(), x.getCategory()));
        boolean passed = record.getOverallResult() == AcceptanceOverallResult.QUALIFIED && !observedAnomaly;

        int streak = passed ? a.getConsecutivePasses() + 1 : 0;
        a.setConsecutivePasses(streak);
        a.setLastCheckPassed(passed);

        AssessmentAcceptanceCheck check = new AssessmentAcceptanceCheck();
        check.setAssessment(a);
        check.setBatch(batch);
        check.setAcceptanceRecord(record);
        check.setPassed(passed);
        check.setConsecutivePasses(streak);
        check.setNote(passed ? "重点验收通过" : "重点验收未通过，连续通过计数清零");
        checkRepo.save(check);
        assessmentRepo.save(a);
        return Optional.of(check);
    }

    /** 连续通过重点验收后，采购负责人恢复正常供货比例并关闭考核 */
    @Transactional
    public SupplierAssessment resume(Long id, String note) {
        SupplierAssessment a = get(id);
        if (a.getStatus() != AssessmentStatus.UNDER_REVIEW) {
            throw BusinessException.conflict("仅整改期(重点验收中)的考核事件可恢复，当前状态: " + a.getStatus());
        }
        if (a.getConsecutivePasses() < a.getRequiredPasses()) {
            throw BusinessException.conflict("尚未连续通过重点验收：" + a.getConsecutivePasses()
                    + "/" + a.getRequiredPasses() + "，不能恢复正常供货");
        }
        Supplier supplier = a.getSupplier();
        supplier.setSuspended(false);
        supplier.setPurchaseRatio(BigDecimal.ONE);
        supplierRepo.save(supplier);

        a.setStatus(AssessmentStatus.CLOSED);
        a.setClosedAt(LocalDateTime.now());
        if (note != null && !note.isBlank()) {
            a.setDecisionNote((a.getDecisionNote() == null ? "" : a.getDecisionNote() + " | ") + note);
        }
        return assessmentRepo.save(a);
    }

    // ============================ 查询 ============================

    public SupplierAssessment get(Long id) {
        return assessmentRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("考核事件不存在: " + id));
    }

    public List<SupplierAssessment> list(Long supplierId, AssessmentStatus status) {
        List<SupplierAssessment> base;
        if (supplierId != null && status != null) {
            base = assessmentRepo.findBySupplierIdAndStatus(supplierId, status);
        } else if (supplierId != null) {
            base = assessmentRepo.findBySupplierIdOrderByCreatedAtDesc(supplierId);
        } else if (status != null) {
            base = assessmentRepo.findByStatusOrderByCreatedAtDesc(status);
        } else {
            base = assessmentRepo.findAll();
        }
        return base.stream()
                .sorted(Comparator.comparing(SupplierAssessment::getCreatedAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public AssessmentDetail detail(Long id) {
        SupplierAssessment a = get(id);
        List<AssessmentBatchEvidence> evidences = evidenceRepo.findByAssessmentIdOrderByIdAsc(id);
        List<AssessmentAcceptanceCheck> checks = checkRepo.findByAssessmentIdOrderByCheckedAtAsc(id);
        boolean eligible = a.getStatus() == AssessmentStatus.UNDER_REVIEW
                && a.getConsecutivePasses() >= a.getRequiredPasses();
        return new AssessmentDetail(a, evidences, checks, eligible);
    }

    /** 供应商当前生效考核状态（无则 null），供看板/计划/送货使用 */
    public Optional<SupplierAssessment> activeAssessment(Long supplierId) {
        return assessmentRepo.findFirstBySupplierIdAndStatusInOrderByCreatedAtDesc(supplierId, ACTIVE_STATUSES);
    }

    /** 整改期：该供应商每次送货需重点（更严格）验收 */
    public boolean isStrictAcceptance(Long supplierId) {
        return activeAssessment(supplierId).map(a -> a.getStatus() == AssessmentStatus.UNDER_REVIEW).orElse(false);
    }

    public boolean isSuspended(Long supplierId) {
        Supplier s = supplierRepo.findById(supplierId).orElse(null);
        return s != null && s.isSuspended();
    }

    public record AssessmentDetail(SupplierAssessment assessment,
                                   List<AssessmentBatchEvidence> evidences,
                                   List<AssessmentAcceptanceCheck> checks,
                                   boolean eligibleForResume) {
    }
}
