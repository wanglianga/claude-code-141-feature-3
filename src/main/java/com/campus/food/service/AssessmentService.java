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

/**
 * 供应商连续异常考核服务。
 *
 * 触发：同一供应商三周（21 天）内多次出现票证缺失、蔬菜腐烂异常（≥3 次）时，
 *      在验收完成后自动生成考核事件（汇总异常批次、验收照片、退货金额、对菜单影响）。
 * 处置：采购负责人可要求整改、暂停供货或降低下月采购比例。
 * 整改：供应商提交整改材料 → 采购确认 → 后续几次送货进入重点验收（更严格验收）；
 *      任一不合格则连续通过次数归零，连续通过 N 次后恢复正常供货与采购比例。
 */
@Service
@RequiredArgsConstructor
public class AssessmentService {

    private static final List<AssessmentStatus> ACTIVE_STATUSES = List.of(
            AssessmentStatus.OPEN, AssessmentStatus.RECTIFICATION,
            AssessmentStatus.MATERIAL_SUBMITTED, AssessmentStatus.ENHANCED_OBSERVATION,
            AssessmentStatus.SUSPENDED);

    private final AssessmentEventRepo assessmentRepo;
    private final RectificationMaterialRepo materialRepo;
    private final AnomalyRecordRepo anomalyRepo;
    private final ReturnOrderRepo returnRepo;
    private final DeliveryItemRepo deliveryItemRepo;
    private final AcceptanceRecordRepo acceptanceRecordRepo;
    private final AcceptanceItemRepo acceptanceItemRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final SupplierRepo supplierRepo;
    private final AppUserRepo userRepo;

    // ---------------------------------------------------------------- 触发

    /**
     * 验收完成后评估：窗口内票证缺失/蔬菜腐烂异常达阈值，且该供应商没有未终结考核时，
     * 自动生成考核事件与 RECURRING_ASSESSMENT 异常。
     */
    @Transactional
    public Optional<AssessmentEvent> evaluateAfterAcceptance(Supplier supplier) {
        if (assessmentRepo.existsBySupplierIdAndStatusIn(supplier.getId(), ACTIVE_STATUSES)) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusDays(AssessmentRules.WINDOW_DAYS);

        List<AnomalyRecord> qualifying = anomalyRepo
                .findBySupplierIdAndTypeInAndDetectedAtBetween(
                        supplier.getId(), AssessmentRules.QUALIFYING_TYPES, windowStart, now)
                .stream()
                .filter(a -> AssessmentRules.qualifies(a.getType(), a.getCategory()))
                .sorted(Comparator.comparing(AnomalyRecord::getDetectedAt))
                .toList();
        if (!AssessmentRules.meetsTrigger(qualifying.size())) {
            return Optional.empty();
        }

        Set<Long> batchIds = new LinkedHashSet<>();
        for (AnomalyRecord a : qualifying) {
            if (a.getBatch() != null) {
                batchIds.add(a.getBatch().getId());
            }
        }
        BigDecimal returnAmount = returnRepo.findBySupplierId(supplier.getId()).stream()
                .filter(r -> !r.getCreatedAt().isBefore(windowStart) && !r.getCreatedAt().isAfter(now))
                .map(ReturnOrder::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int impactedMeals = collectImpactedMeals(batchIds).size();

        AssessmentEvent event = new AssessmentEvent();
        event.setEventNo(NoGen.next("AS"));
        event.setSupplier(supplier);
        event.setStatus(AssessmentStatus.OPEN);
        event.setTriggeredAt(now);
        event.setWindowStart(windowStart);
        event.setWindowEnd(now);
        event.setQualifyingAnomalyCount(qualifying.size());
        event.setQualifyingAnomalyIds(joinIds(qualifying.stream().map(AnomalyRecord::getId).toList()));
        event.setCoveredBatchIds(joinIds(new ArrayList<>(batchIds)));
        event.setTotalReturnAmount(returnAmount);
        event.setImpactedMealCount(impactedMeals);
        event.setRequiredConsecutivePasses(AssessmentRules.REQUIRED_CONSECUTIVE_PASSES);
        event.setSummary(buildSummary(supplier, qualifying, batchIds.size(), returnAmount, impactedMeals));

        AnomalyRecord anomaly = new AnomalyRecord();
        anomaly.setAnomalyNo(NoGen.next("AN"));
        anomaly.setSupplier(supplier);
        anomaly.setType(AnomalyType.RECURRING_ASSESSMENT);
        anomaly.setCategory(IngredientCategory.VEGETABLE);
        anomaly.setStatus(AnomalyStatus.OPEN);
        anomaly.setDescription("三周内连续发生 " + qualifying.size()
                + " 次票证缺失/蔬菜腐烂异常，自动生成供应商考核事件 " + event.getEventNo());
        anomaly.setPenaltyAmount(PenaltyRules.penaltyFor(AnomalyType.RECURRING_ASSESSMENT, null));
        anomalyRepo.save(anomaly);
        event.setAnomaly(anomaly);

        return Optional.of(assessmentRepo.save(event));
    }

    private String buildSummary(Supplier supplier, List<AnomalyRecord> qualifying,
                                int batchCount, BigDecimal returnAmount, int impactedMeals) {
        long missing = qualifying.stream().filter(a -> a.getType() == AnomalyType.MISSING_DOCUMENTS).count();
        long rot = qualifying.stream().filter(a -> a.getType() == AnomalyType.HIGH_ROT_RATIO).count();
        return "供应商[" + supplier.getName() + "]近三周累计票证缺失 " + missing + " 次、蔬菜腐烂超标 "
                + rot + " 次，涉及异常批次 " + batchCount + " 个，退货金额 " + returnAmount
                + " 元，影响未来7天餐次 " + impactedMeals + " 个，触发连续异常考核。";
    }

    // ---------------------------------------------------------------- 查询

    public AssessmentEvent get(Long id) {
        return assessmentRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("考核事件不存在: " + id));
    }

    public List<AssessmentEvent> list(Long supplierId, AssessmentStatus status) {
        List<AssessmentEvent> base = supplierId != null
                ? assessmentRepo.findBySupplierIdOrderByTriggeredAtDesc(supplierId)
                : assessmentRepo.findAllByOrderByTriggeredAtDesc();
        return base.stream()
                .filter(e -> status == null || e.getStatus() == status)
                .toList();
    }

    /** 按当前用户角色可见范围列出考核事件（供应商账号仅可见本供应商） */
    @Transactional(readOnly = true)
    public List<AssessmentEvent> listForUser(String username, Long supplierId, AssessmentStatus status) {
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> BusinessException.notFound("用户不存在: " + username));
        Long scope;
        if (user.getRole() == Role.SUPPLIER) {
            Long ownId = user.getSupplier() == null ? -1L : user.getSupplier().getId();
            // 显式查询他人供应商时返回空；不传或查本供应商时只返回本供应商
            scope = supplierId != null && !supplierId.equals(ownId) ? -1L : ownId;
        } else {
            scope = supplierId;
        }
        return list(scope, status);
    }

    /** 按当前用户角色读取考核详情（供应商账号仅限本供应商） */
    @Transactional(readOnly = true)
    public EventDetail detailForUser(Long id, String username) {
        EventDetail detail = detail(id);
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> BusinessException.notFound("用户不存在: " + username));
        if (user.getRole() == Role.SUPPLIER
                && (user.getSupplier() == null
                || !user.getSupplier().getId().equals(detail.event().getSupplier().getId()))) {
            throw BusinessException.notFound("考核事件不存在: " + id);
        }
        return detail;
    }

    /** 供应商当前是否处于整改观察（重点验收）期 */
    public Optional<AssessmentEvent> currentEnhancedEvent(Long supplierId) {
        return assessmentRepo.findFirstBySupplierIdAndStatusInOrderByTriggeredAtDesc(
                supplierId, List.of(AssessmentStatus.ENHANCED_OBSERVATION));
    }

    /** 考核事件详情：含命中异常、异常批次证据（验收照片/退货金额）、菜单影响、整改材料 */
    @Transactional(readOnly = true)
    public EventDetail detail(Long id) {
        AssessmentEvent event = get(id);
        List<Long> anomalyIds = splitIds(event.getQualifyingAnomalyIds());
        List<AnomalyRecord> anomalies = anomalyIds.isEmpty() ? List.of() : anomalyRepo.findAllById(anomalyIds);

        List<Long> batchIds = splitIds(event.getCoveredBatchIds());
        List<BatchEvidence> batches = new ArrayList<>();
        List<String> allPhotos = new ArrayList<>();
        for (Long batchId : batchIds) {
            AcceptanceRecord record = acceptanceRecordRepo.findByBatchId(batchId).orElse(null);
            DeliveryBatch batch = record != null ? record.getBatch() : null;
            List<String> recordPhotos = splitCsv(record == null ? null : record.getPhotos());
            List<ItemPhoto> itemPhotos = new ArrayList<>();
            BigDecimal batchReturn = BigDecimal.ZERO;
            if (record != null) {
                allPhotos.addAll(recordPhotos);
                for (AcceptanceItem ai : acceptanceItemRepo.findByRecordId(record.getId())) {
                    List<String> photos = splitCsv(ai.getPhotos());
                    if (!photos.isEmpty()) {
                        itemPhotos.add(new ItemPhoto(ai.getIngredient().getName(),
                                ai.getResult().name(), photos));
                        allPhotos.addAll(photos);
                    }
                }
                batchReturn = returnRepo.findBySupplierId(event.getSupplier().getId()).stream()
                        .filter(r -> r.getAcceptanceItem() != null
                                && r.getAcceptanceItem().getRecord().getId().equals(record.getId()))
                        .map(ReturnOrder::getRefundAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            batches.add(new BatchEvidence(
                    batchId,
                    batch == null ? null : batch.getBatchNo(),
                    batch == null ? null : batch.getArrivedAt(),
                    record == null ? null : record.getRecordNo(),
                    record == null ? null : record.getInspectionLevel(),
                    recordPhotos, itemPhotos, batchReturn));
        }

        List<MealRef> mealImpacts = new ArrayList<>(collectImpactedMeals(new LinkedHashSet<>(batchIds)).values());
        List<RectificationMaterial> materials =
                materialRepo.findByAssessmentIdOrderBySubmittedAtDesc(event.getId());

        return new EventDetail(event, anomalies, batches, allPhotos, mealImpacts, materials);
    }

    /** 收集异常批次食材对未来 7 天餐次的影响（去重） */
    private LinkedHashMap<Long, MealRef> collectImpactedMeals(Set<Long> batchIds) {
        LocalDate today = LocalDate.now();
        LinkedHashMap<Long, MealRef> meals = new LinkedHashMap<>();
        for (Long batchId : batchIds) {
            for (DeliveryItem di : deliveryItemRepo.findByBatchId(batchId)) {
                mealIngredientRepo.findMealsNeeding(di.getIngredient().getId(), today, today.plusDays(7),
                                List.of(MealStatus.SCHEDULED, MealStatus.PREPARED))
                        .forEach(mmi -> {
                            MenuMeal m = mmi.getMeal();
                            meals.putIfAbsent(m.getId(),
                                    new MealRef(m.getId(), m.getDate(), m.getMealType(), m.getDishName()));
                        });
            }
        }
        return meals;
    }

    // ---------------------------------------------------------------- 采购处置

    /** 要求限期整改（供应商提交整改材料后进入重点验收流程） */
    @Transactional
    public AssessmentEvent requireRectification(Long eventId, String username,
                                                LocalDate deadline, String note) {
        AssessmentEvent e = get(eventId);
        if (e.getStatus() == AssessmentStatus.SUSPENDED
                || e.getStatus() == AssessmentStatus.ENHANCED_OBSERVATION
                || e.getStatus() == AssessmentStatus.RESOLVED
                || e.getStatus() == AssessmentStatus.CLOSED) {
            throw BusinessException.conflict("当前考核状态[" + e.getStatus() + "]不允许要求整改");
        }
        if (deadline == null || deadline.isBefore(LocalDate.now())) {
            throw BusinessException.badRequest("必须提供不早于今天的整改截止日期");
        }
        e.setStatus(AssessmentStatus.RECTIFICATION);
        e.setDecision(AssessmentDecision.RECTIFY);
        e.setDecisionNote(note);
        e.setRectificationDeadline(deadline);
        stampDecision(e, username);
        return assessmentRepo.save(e);
    }

    /** 暂停供货（停用供应商，新采购计划不再分配；放行需确认整改材料） */
    @Transactional
    public AssessmentEvent suspend(Long eventId, String username, String note) {
        AssessmentEvent e = get(eventId);
        if (e.getStatus() == AssessmentStatus.RESOLVED || e.getStatus() == AssessmentStatus.CLOSED
                || e.getStatus() == AssessmentStatus.ENHANCED_OBSERVATION) {
            throw BusinessException.conflict("当前考核状态[" + e.getStatus() + "]不允许暂停供货");
        }
        e.setStatus(AssessmentStatus.SUSPENDED);
        e.setDecision(AssessmentDecision.SUSPEND);
        e.setDecisionNote(note);
        stampDecision(e, username);
        Supplier s = e.getSupplier();
        s.setActive(false);
        supplierRepo.save(s);
        return assessmentRepo.save(e);
    }

    /** 降低下月采购比例（0~1，必须低于当前比例）；可与整改/暂停措施叠加 */
    @Transactional
    public AssessmentEvent reduceRatio(Long eventId, String username, BigDecimal ratio, String note) {
        AssessmentEvent e = get(eventId);
        if (List.of(AssessmentStatus.RESOLVED, AssessmentStatus.CLOSED).contains(e.getStatus())) {
            throw BusinessException.conflict("考核已终结，不能再调整采购比例");
        }
        if (ratio == null || ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) >= 0) {
            throw BusinessException.badRequest("采购比例必须在 0~1 之间（不含 0 与 1）");
        }
        if (ratio.compareTo(e.getSupplier().getPurchaseRatio()) >= 0) {
            throw BusinessException.badRequest("降低后的比例(" + ratio + ")必须低于当前比例("
                    + e.getSupplier().getPurchaseRatio() + ")");
        }
        e.setNextMonthPurchaseRatio(ratio);
        e.setDecisionNote(appendNote(e.getDecisionNote(), "降低下月采购比例至 " + ratio
                + (note == null ? "" : "：" + note)));
        stampDecision(e, username);
        Supplier s = e.getSupplier();
        s.setPurchaseRatio(ratio);
        supplierRepo.save(s);
        return assessmentRepo.save(e);
    }

    /** 直接关闭考核（不进入整改流程） */
    @Transactional
    public AssessmentEvent close(Long eventId, String username, String note) {
        AssessmentEvent e = get(eventId);
        if (e.getStatus() == AssessmentStatus.RESOLVED || e.getStatus() == AssessmentStatus.CLOSED) {
            throw BusinessException.conflict("考核已终结");
        }
        if (e.getStatus() == AssessmentStatus.SUSPENDED) {
            throw BusinessException.conflict("暂停供货状态不能直接关闭，请先确认整改材料放行");
        }
        e.setStatus(AssessmentStatus.CLOSED);
        e.setResolutionNote(note);
        e.setResolvedAt(LocalDateTime.now());
        stampDecision(e, username);
        if (e.getAnomaly() != null && e.getAnomaly().getStatus() != AnomalyStatus.RESOLVED) {
            AnomalyRecord a = e.getAnomaly();
            a.setStatus(AnomalyStatus.RESOLVED);
            a.setResolvedAt(LocalDateTime.now());
            a.setHandlingNote("考核事件关闭：" + note);
            anomalyRepo.save(a);
        }
        return assessmentRepo.save(e);
    }

    // ---------------------------------------------------------------- 整改材料

    /** 供应商提交整改材料（整改期内或暂停放行前均可提交） */
    @Transactional
    public RectificationMaterial submitMaterial(Long eventId, String username,
                                                String title, String content, String attachments) {
        AssessmentEvent e = get(eventId);
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> BusinessException.notFound("用户不存在: " + username));
        if (user.getSupplier() == null || !user.getSupplier().getId().equals(e.getSupplier().getId())) {
            throw BusinessException.conflict("只能为本供应商的考核提交整改材料");
        }
        if (e.getStatus() != AssessmentStatus.RECTIFICATION && e.getStatus() != AssessmentStatus.SUSPENDED) {
            throw BusinessException.conflict("当前考核状态[" + e.getStatus() + "]不允许提交整改材料");
        }
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            throw BusinessException.badRequest("整改标题与内容不能为空");
        }
        RectificationMaterial m = new RectificationMaterial();
        m.setAssessment(e);
        m.setSubmittedBy(user);
        m.setTitle(title);
        m.setContent(content);
        m.setAttachments(attachments);
        materialRepo.save(m);

        if (e.getStatus() == AssessmentStatus.RECTIFICATION) {
            e.setStatus(AssessmentStatus.MATERIAL_SUBMITTED);
            assessmentRepo.save(e);
        }
        return m;
    }

    /**
     * 采购负责人确认整改材料：后续送货进入重点验收（整改观察期）。
     * 若供应商处于暂停状态，同时恢复其供货资格。
     */
    @Transactional
    public AssessmentEvent confirmMaterial(Long materialId, String username,
                                           Integer requiredPasses, String reviewNote) {
        RectificationMaterial m = materialRepo.findById(materialId)
                .orElseThrow(() -> BusinessException.notFound("整改材料不存在: " + materialId));
        AssessmentEvent e = m.getAssessment();
        if (e.getStatus() != AssessmentStatus.MATERIAL_SUBMITTED && e.getStatus() != AssessmentStatus.SUSPENDED) {
            throw BusinessException.conflict("当前考核状态[" + e.getStatus() + "]不能确认整改材料");
        }
        m.setReviewNote(reviewNote);
        m.setReviewedAt(LocalDateTime.now());
        materialRepo.save(m);

        e.setStatus(AssessmentStatus.ENHANCED_OBSERVATION);
        e.setEnhancedStartedAt(LocalDateTime.now());
        e.setConsecutivePasses(0);
        e.setEnhancedInspectionCount(0);
        if (requiredPasses != null && requiredPasses > 0) {
            e.setRequiredConsecutivePasses(requiredPasses);
        }
        stampDecision(e, username);
        assessmentRepo.save(e);

        if (!e.getSupplier().isActive()) {
            Supplier s = e.getSupplier();
            s.setActive(true);
            supplierRepo.save(s);
        }
        return e;
    }

    // ---------------------------------------------------------------- 重点验收结果

    /**
     * 登记整改观察期内一次重点验收结果：
     * 通过则累计连续次数，达到要求后恢复正常供货比例并终结考核；不通过则连续次数归零。
     */
    @Transactional
    public AssessmentEvent recordEnhancedOutcome(AcceptanceRecord record, boolean passed) {
        Supplier supplier = record.getBatch().getOrder().getSupplier();
        AssessmentEvent e = currentEnhancedEvent(supplier.getId())
                .orElseThrow(() -> BusinessException.conflict("供应商不在整改观察期，无需登记重点验收结果"));
        e.setEnhancedInspectionCount(e.getEnhancedInspectionCount() + 1);
        if (passed) {
            e.setConsecutivePasses(e.getConsecutivePasses() + 1);
            if (AssessmentRules.passesRequirement(e.getConsecutivePasses(), e.getRequiredConsecutivePasses())) {
                e.setStatus(AssessmentStatus.RESOLVED);
                e.setResolvedAt(LocalDateTime.now());
                e.setResolutionNote("整改观察期内连续 " + e.getConsecutivePasses()
                        + " 次重点验收通过，恢复正常供货比例");
                if (e.getAnomaly() != null && e.getAnomaly().getStatus() != AnomalyStatus.RESOLVED) {
                    AnomalyRecord a = e.getAnomaly();
                    a.setStatus(AnomalyStatus.RESOLVED);
                    a.setResolvedAt(LocalDateTime.now());
                    a.setCauseCategory(CauseCategory.SUPPLIER_PERFORMANCE);
                    a.setHandlingNote("整改观察连续 " + e.getConsecutivePasses() + " 次重点验收通过，自动关闭");
                    anomalyRepo.save(a);
                }
                if (supplier.getPurchaseRatio().compareTo(BigDecimal.ONE) != 0) {
                    supplier.setPurchaseRatio(BigDecimal.ONE);
                    supplierRepo.save(supplier);
                }
            }
        } else {
            e.setConsecutivePasses(0);
        }
        return assessmentRepo.save(e);
    }

    // ---------------------------------------------------------------- 辅助

    private void stampDecision(AssessmentEvent e, String username) {
        AppUser user = userRepo.findByUsername(username).orElse(null);
        e.setDecidedBy(user);
        e.setDecidedAt(LocalDateTime.now());
    }

    private static String appendNote(String oldNote, String add) {
        if (oldNote == null || oldNote.isBlank()) {
            return add;
        }
        return oldNote + "；" + add;
    }

    private static String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(null);
    }

    private static List<Long> splitIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).toList();
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // ---------------------------------------------------------------- DTO

    public record MealRef(Long mealId, LocalDate date, MealType mealType, String dishName) {
    }

    public record ItemPhoto(String ingredientName, String itemResult, List<String> photos) {
    }

    public record BatchEvidence(Long batchId, String batchNo, LocalDateTime arrivedAt,
                                String acceptanceRecordNo, InspectionLevel inspectionLevel,
                                List<String> recordPhotos, List<ItemPhoto> itemPhotos,
                                BigDecimal returnAmount) {
    }

    public record EventDetail(AssessmentEvent event, List<AnomalyRecord> qualifyingAnomalies,
                              List<BatchEvidence> batches, List<String> photos,
                              List<MealRef> impactedMeals,
                              List<RectificationMaterial> materials) {
    }
}
