package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 异常服务：登记、处理、供应商看板 */
@Service
@RequiredArgsConstructor
public class AnomalyService {

    private final AnomalyRecordRepo anomalyRepo;
    private final SupplierRepo supplierRepo;
    private final DeliveryItemRepo deliveryItemRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final AssessmentService assessmentService;

    /** 登记异常并自动计算建议扣罚金额；票证缺失/蔬菜腐烂在三周内多次出现时自动生成考核事件 */
    @Transactional
    public AnomalyRecord create(Supplier supplier, PurchaseOrder order, DeliveryBatch batch,
                                AnomalyType type, IngredientCategory category,
                                String description, BigDecimal penaltyBase) {
        AnomalyRecord a = new AnomalyRecord();
        a.setAnomalyNo(NoGen.next("AN"));
        a.setSupplier(supplier);
        a.setOrder(order);
        a.setBatch(batch);
        a.setType(type);
        a.setCategory(category);
        a.setDescription(description);
        a.setPenaltyAmount(PenaltyRules.penaltyFor(type, penaltyBase));
        return anomalyRepo.save(a);
    }

    /**
     * 验收事务结束后评估是否触发连续异常考核（由验收服务在退货单等落库后调用，
     * 以便考核事件能汇总到退货金额）。票证缺失/腐烂异常均在验收环节产生。
     */
    @Transactional
    public void evaluateAssessment(Long supplierId) {
        assessmentService.evaluateAfterAnomaly(supplierId);
    }

    public List<AnomalyRecord> list(Long supplierId, AnomalyType type, AnomalyStatus status) {
        List<AnomalyRecord> base = supplierId != null ? anomalyRepo.findBySupplierId(supplierId) : anomalyRepo.findAll();
        return base.stream()
                .filter(a -> type == null || a.getType() == type)
                .filter(a -> status == null || a.getStatus() == status)
                .sorted(Comparator.comparing(AnomalyRecord::getDetectedAt).reversed())
                .toList();
    }

    public AnomalyRecord get(Long id) {
        return anomalyRepo.findById(id).orElseThrow(() -> BusinessException.notFound("异常不存在: " + id));
    }

    @Transactional
    public AnomalyRecord markProcessing(Long id) {
        AnomalyRecord a = get(id);
        if (a.getStatus() == AnomalyStatus.RESOLVED) {
            throw BusinessException.conflict("异常已关闭，不能再变更状态");
        }
        a.setStatus(AnomalyStatus.PROCESSING);
        return anomalyRepo.save(a);
    }

    /** 关闭异常并归因（复盘统计依据） */
    @Transactional
    public AnomalyRecord resolve(Long id, CauseCategory cause, String note) {
        AnomalyRecord a = get(id);
        if (a.getStatus() == AnomalyStatus.RESOLVED) {
            throw BusinessException.conflict("异常已关闭");
        }
        a.setStatus(AnomalyStatus.RESOLVED);
        a.setResolvedAt(LocalDateTime.now());
        a.setCauseCategory(cause == null ? CauseCategory.OTHER : cause);
        a.setHandlingNote(note);
        return anomalyRepo.save(a);
    }

    /** 供应商异常看板 */
    public SupplierDashboard dashboard(Long supplierId) {
        Supplier supplier = supplierRepo.findById(supplierId)
                .orElseThrow(() -> BusinessException.notFound("供应商不存在: " + supplierId));
        List<AnomalyRecord> all = anomalyRepo.findBySupplierId(supplierId);

        Map<AnomalyType, Long> byType = all.stream()
                .collect(Collectors.groupingBy(AnomalyRecord::getType, Collectors.counting()));
        long open = all.stream().filter(a -> a.getStatus() != AnomalyStatus.RESOLVED).count();

        List<AnomalyRecord> resolved = all.stream()
                .filter(a -> a.getStatus() == AnomalyStatus.RESOLVED && a.getResolvedAt() != null)
                .toList();
        double avgHandlingMinutes = resolved.stream()
                .mapToLong(a -> Duration.between(a.getDetectedAt(), a.getResolvedAt()).toMinutes())
                .average().orElse(0);

        long last30d = anomalyRepo.countBySupplierIdAndDetectedAtAfter(
                supplierId, LocalDateTime.now().minusDays(30));
        boolean consecutiveAnomaly = last30d >= 3;

        // 未关闭异常对菜单的影响：涉及食材在未来 7 天餐次中的出现次数
        Set<Long> impactedMealIds = new HashSet<>();
        LocalDate today = LocalDate.now();
        for (AnomalyRecord a : all) {
            if (a.getStatus() == AnomalyStatus.RESOLVED || a.getBatch() == null) {
                continue;
            }
            for (DeliveryItem di : deliveryItemRepo.findByBatchId(a.getBatch().getId())) {
                mealIngredientRepo.findMealsNeeding(di.getIngredient().getId(), today, today.plusDays(7),
                                List.of(MealStatus.SCHEDULED, MealStatus.PREPARED))
                        .forEach(mmi -> impactedMealIds.add(mmi.getMeal().getId()));
            }
        }

        BigDecimal totalPenalty = all.stream()
                .map(AnomalyRecord::getPenaltyAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        SupplierAssessment active = assessmentService.activeAssessment(supplierId).orElse(null);

        return new SupplierDashboard(
                supplier.getId(), supplier.getCode(), supplier.getName(),
                all.size(), open, byType, avgHandlingMinutes, last30d, consecutiveAnomaly,
                impactedMealIds.size(), totalPenalty,
                supplier.isSuspended(), supplier.getPurchaseRatio(),
                active == null ? null : active.getAssessmentNo(),
                active == null ? null : active.getStatus(),
                active == null ? 0 : active.getConsecutivePasses(),
                active == null ? 0 : active.getRequiredPasses());
    }

    /** 供应商异常看板 */
    public record SupplierDashboard(
            Long supplierId,
            String supplierCode,
            String supplierName,
            long totalAnomalies,
            long openAnomalies,
            Map<AnomalyType, Long> anomaliesByType,
            double avgHandlingMinutes,
            long anomaliesLast30Days,
            boolean consecutiveAnomaly,
            long impactedUpcomingMeals,
            BigDecimal totalPenaltyAmount,
            boolean suspended,
            BigDecimal purchaseRatio,
            String assessmentNo,
            AssessmentStatus assessmentStatus,
            int assessmentConsecutivePasses,
            int assessmentRequiredPasses) {
    }
}
