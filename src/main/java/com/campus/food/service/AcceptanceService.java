package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 验收服务：核对温度/外观/票证/重量/腐烂比例/抽检，
 * 合格入库、拒收退货、降级待审批；自动识别少送、腐烂超标、票证缺失、已备餐无法拒收等异常。
 */
@Service
@RequiredArgsConstructor
public class AcceptanceService {

    private final AcceptanceRecordRepo recordRepo;
    private final AcceptanceItemRepo itemRepo;
    private final DeliveryBatchRepo batchRepo;
    private final DeliveryItemRepo deliveryItemRepo;
    private final AppUserRepo userRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final PurchaseOrderRepo orderRepo;
    private final InventoryService inventoryService;
    private final AnomalyService anomalyService;
    private final AssessmentService assessmentService;
    private final ReturnService returnService;
    private final ClaimService claimService;

    public record AcceptanceItemInput(Long deliveryItemId, BigDecimal acceptedQty, BigDecimal rejectedQty,
                                      BigDecimal downgradedQty, BigDecimal rotRatio, String rejectReason,
                                      List<String> photos) {
    }

    @Transactional
    public AcceptanceRecord create(String inspectorUsername, Long batchId,
                                   BigDecimal measuredTemp, Boolean temperatureOk, Boolean appearanceOk,
                                   String samplingResult, String notes, List<String> recordPhotos,
                                   List<AcceptanceItemInput> items) {
        DeliveryBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> BusinessException.notFound("送货批次不存在: " + batchId));
        if (recordRepo.existsByBatchId(batchId)) {
            throw BusinessException.conflict("该送货批次已验收，不能重复验收");
        }
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("验收明细不能为空");
        }
        AppUser inspector = userRepo.findByUsername(inspectorUsername)
                .orElseThrow(() -> BusinessException.notFound("验收员不存在: " + inspectorUsername));
        PurchaseOrder order = batch.getOrder();
        Supplier supplier = order.getSupplier();

        // 整改观察期内的供应商，本次送货强制执行重点（更严格）验收
        boolean enhanced = assessmentService.currentEnhancedEvent(supplier.getId()).isPresent();
        if (enhanced) {
            if (recordPhotos == null || recordPhotos.isEmpty()) {
                throw BusinessException.badRequest("该供应商处于整改观察期，重点验收必须上传验收单级照片（车厢/现场/抽检）");
            }
            if (samplingResult == null || samplingResult.isBlank()) {
                throw BusinessException.badRequest("该供应商处于整改观察期，重点验收必须填写加倍抽检结果");
            }
            if (!Boolean.TRUE.equals(temperatureOk) || !Boolean.TRUE.equals(appearanceOk)) {
                throw BusinessException.badRequest("重点验收要求温度与外观检查均合格（temperatureOk/appearanceOk 必须为 true）");
            }
        }

        AcceptanceRecord record = new AcceptanceRecord();
        record.setRecordNo(NoGen.next("AR"));
        record.setBatch(batch);
        record.setInspector(inspector);
        record.setMeasuredTemp(measuredTemp);
        record.setTemperatureOk(temperatureOk);
        record.setAppearanceOk(appearanceOk);
        record.setSamplingResult(samplingResult);
        record.setNotes(notes);
        record.setInspectionLevel(enhanced ? InspectionLevel.ENHANCED : InspectionLevel.NORMAL);
        record.setPhotos(toCsv(recordPhotos));

        boolean anyNotQualified = false;
        boolean anyAccepted = false;
        // 重点验收（整改观察期）本次是否出现任一不合格项/异常
        boolean enhancedFail = false;
        List<AcceptanceItem> savedItems = new ArrayList<>();
        // 待执行的后续动作（入库/退货），需在验收单与明细落库后执行
        List<Runnable> postActions = new ArrayList<>();

        for (AcceptanceItemInput in : items) {
            DeliveryItem di = deliveryItemRepo.findById(in.deliveryItemId())
                    .orElseThrow(() -> BusinessException.notFound("送货明细不存在: " + in.deliveryItemId()));
            if (!di.getBatch().getId().equals(batch.getId())) {
                throw BusinessException.badRequest("送货明细不属于该批次: " + in.deliveryItemId());
            }
            Ingredient ingredient = di.getIngredient();
            OrderItem oi = di.getOrderItem();
            BigDecimal price = oi != null ? oi.getContractPrice() : ingredient.getReferencePrice();
            BigDecimal orderedQty = oi != null ? oi.getQuantity() : BigDecimal.ZERO;

            // 重点验收：逐项必须上传照片（称重/外观/腐烂/票证特写）
            if (enhanced && (in.photos() == null || in.photos().isEmpty())) {
                throw BusinessException.badRequest("重点验收要求逐项上传验收照片：[" + ingredient.getName() + "] 缺少照片");
            }

            BigDecimal accepted = nvl(in.acceptedQty());
            BigDecimal rejected = nvl(in.rejectedQty());
            BigDecimal downgraded = nvl(in.downgradedQty());
            BigDecimal sum = accepted.add(rejected).add(downgraded);
            if (sum.compareTo(di.getDeliveredQty()) != 0) {
                throw BusinessException.badRequest("[" + ingredient.getName() + "] 验收数量合计 "
                        + sum + " 与送货数量 " + di.getDeliveredQty() + " 不一致");
            }

            AcceptanceItemResult result;
            // 食堂已备餐 -> 无法拒收，转附条件接收并自动索赔
            if (rejected.signum() > 0
                    && mealIngredientRepo.countPreparedMealsNeeding(ingredient.getId(), LocalDate.now()) > 0) {
                BigDecimal conditionalQty = rejected;
                accepted = accepted.add(rejected);
                rejected = BigDecimal.ZERO;
                AnomalyRecord anomaly = anomalyService.create(supplier, order, batch,
                        AnomalyType.CANNOT_REJECT_PREPARED, ingredient.getCategory(),
                        "食堂已备餐，无法拒收[" + ingredient.getName() + "] " + conditionalQty
                                + ingredient.getUnit() + "，转附条件接收",
                        conditionalQty.multiply(price));
                claimService.createInternal(supplier, batch, anomaly, "无法拒收折价索赔",
                        conditionalQty.multiply(price).multiply(new BigDecimal("0.5"))
                                .setScale(2, RoundingMode.HALF_UP),
                        "已备餐无法拒收，按货值 50% 索赔折价");
                result = AcceptanceItemResult.CONDITIONAL_ACCEPT;
            } else if (rejected.signum() > 0 && accepted.signum() == 0 && downgraded.signum() == 0) {
                result = AcceptanceItemResult.REJECTED;
            } else if (downgraded.signum() > 0 && rejected.signum() == 0 && accepted.signum() == 0) {
                result = AcceptanceItemResult.DOWNGRADED;
            } else if (rejected.signum() > 0 || downgraded.signum() > 0) {
                result = AcceptanceItemResult.PARTIAL;
            } else {
                result = AcceptanceItemResult.QUALIFIED;
            }

            AcceptanceItem item = new AcceptanceItem();
            item.setRecord(record);
            item.setDeliveryItem(di);
            item.setIngredient(ingredient);
            item.setOrderedQty(orderedQty);
            item.setDeliveredQty(di.getDeliveredQty());
            item.setAcceptedQty(accepted);
            item.setRejectedQty(rejected);
            item.setDowngradedQty(downgraded);
            item.setRotRatio(in.rotRatio());
            item.setResult(result);
            item.setRejectReason(in.rejectReason());
            item.setPhotos(toCsv(in.photos()));
            if (downgraded.signum() > 0) {
                item.setDowngradeStatus(DowngradeStatus.PENDING);
            }
            item.setRecord(record);
            savedItems.add(item);
            if (enhanced && result != AcceptanceItemResult.QUALIFIED) {
                enhancedFail = true;
            }

            // 少送异常（按订货明细累计送货量判定，避免分批送货误报）
            if (oi != null) {
                BigDecimal deliveredBefore = deliveryItemRepo
                        .sumDeliveredExcludingBatch(oi.getId(), batch.getId());
                BigDecimal remaining = oi.getQuantity().subtract(deliveredBefore).max(BigDecimal.ZERO);
                if (di.getDeliveredQty().compareTo(remaining) < 0) {
                    BigDecimal shortQty = remaining.subtract(di.getDeliveredQty());
                    anomalyService.create(supplier, order, batch, AnomalyType.SHORT_DELIVERY,
                            ingredient.getCategory(),
                            "[" + ingredient.getName() + "] 订购 " + oi.getQuantity()
                                    + "，此前累计已送 " + deliveredBefore + "，本次送 "
                                    + di.getDeliveredQty() + "，少送 " + shortQty + ingredient.getUnit(),
                            shortQty.multiply(price));
                    enhancedFail = true;
                }
            }
            // 腐烂比例异常：整改观察期执行更严格阈值（蔬果 5%、其他 3%），常规为蔬果 10%、其他 5%
            BigDecimal threshold = enhanced
                    ? AssessmentRules.enhancedRotThreshold(ingredient.getCategory())
                    : switch (ingredient.getCategory()) {
                        case VEGETABLE, FRUIT -> new BigDecimal("0.10");
                        default -> new BigDecimal("0.05");
                    };
            if (in.rotRatio() != null && in.rotRatio().compareTo(threshold) > 0) {
                anomalyService.create(supplier, order, batch, AnomalyType.HIGH_ROT_RATIO,
                        ingredient.getCategory(),
                        "[" + ingredient.getName() + "] 腐烂比例 " + in.rotRatio()
                                + " 超过" + (enhanced ? "重点验收" : "常规") + "阈值 " + threshold,
                        di.getDeliveredQty().multiply(in.rotRatio()).multiply(price));
                enhancedFail = true;
            }
            // 票证缺失：肉类需动物检疫合格证明；蔬果需农残检测/质量合格证明（整改期重点核查）
            String missingDoc = null;
            if (ingredient.getCategory() == IngredientCategory.MEAT
                    && (di.getQuarantineCertNo() == null || di.getQuarantineCertNo().isBlank())) {
                missingDoc = "缺少动物检疫合格证明";
            } else if ((ingredient.getCategory() == IngredientCategory.VEGETABLE
                    || ingredient.getCategory() == IngredientCategory.FRUIT)
                    && (di.getQualityCertNo() == null || di.getQualityCertNo().isBlank())) {
                missingDoc = "缺少农残检测/质量合格证明";
            }
            if (missingDoc != null) {
                anomalyService.create(supplier, order, batch, AnomalyType.MISSING_DOCUMENTS,
                        ingredient.getCategory(),
                        "[" + ingredient.getName() + "] " + missingDoc, null);
                enhancedFail = true;
            }

            // 库存与退货（延迟到验收单与明细落库后执行）
            final BigDecimal acceptedFinal = accepted;
            final BigDecimal rejectedFinal = rejected;
            final String reasonFinal = in.rejectReason() == null ? "验收不合格" : in.rejectReason();
            final BigDecimal priceFinal = price;
            if (accepted.signum() > 0) {
                postActions.add(() -> inventoryService.change(ingredient.getId(), acceptedFinal,
                        InventoryTxnType.IN, "ACCEPTANCE", record.getId(),
                        "验收入库 " + record.getRecordNo()));
                anyAccepted = true;
            }
            if (rejected.signum() > 0) {
                postActions.add(() -> returnService.createInternal(supplier, item, ingredient,
                        rejectedFinal, reasonFinal, rejectedFinal.multiply(priceFinal)));
            }
            if (result != AcceptanceItemResult.QUALIFIED) {
                anyNotQualified = true;
            }
        }

        record.setOverallResult(!anyNotQualified ? AcceptanceOverallResult.QUALIFIED
                : anyAccepted ? AcceptanceOverallResult.PARTIAL_QUALIFIED
                : AcceptanceOverallResult.REJECTED);
        if (enhanced) {
            // 重点验收通过 = 整体合格、无任何拒收/降级/少送/腐烂/票证问题
            record.setEnhancedPassed(!enhancedFail && !anyNotQualified);
        }
        recordRepo.save(record);
        savedItems.forEach(itemRepo::save);
        postActions.forEach(Runnable::run);

        order.setStatus(OrderStatus.ACCEPTED);
        orderRepo.save(order);

        if (enhanced) {
            // 登记重点验收结果：通过累计连续次数（达标自动恢复正常供货），不通过则连续次数归零
            assessmentService.recordEnhancedOutcome(record, record.getEnhancedPassed());
        } else {
            // 常规验收后评估是否触发"三周内多次票证缺失/蔬菜腐烂"连续异常考核
            assessmentService.evaluateAfterAcceptance(supplier);
        }
        return record;
    }

    /** 降级使用审批：批准则按折价入库，驳回则转退货 */
    @Transactional
    public AcceptanceItem approveDowngrade(Long acceptanceItemId, boolean approve, BigDecimal discountRate) {
        AcceptanceItem item = itemRepo.findById(acceptanceItemId)
                .orElseThrow(() -> BusinessException.notFound("验收明细不存在: " + acceptanceItemId));
        if (item.getDowngradeStatus() != DowngradeStatus.PENDING) {
            throw BusinessException.conflict("该明细没有待审批的降级数量");
        }
        Ingredient ingredient = item.getIngredient();
        DeliveryItem di = item.getDeliveryItem();
        OrderItem oi = di.getOrderItem();
        BigDecimal price = oi != null ? oi.getContractPrice() : ingredient.getReferencePrice();
        Supplier supplier = item.getRecord().getBatch().getOrder().getSupplier();

        if (approve) {
            item.setDowngradeStatus(DowngradeStatus.APPROVED);
            item.setDiscountRate(discountRate == null ? new BigDecimal("0.20") : discountRate);
            inventoryService.change(ingredient.getId(), item.getDowngradedQty(), InventoryTxnType.IN,
                    "DOWNGRADE", item.getId(), "降级使用入库（折价率 " + item.getDiscountRate() + "）");
        } else {
            item.setDowngradeStatus(DowngradeStatus.REJECTED);
            BigDecimal qty = item.getDowngradedQty();
            item.setRejectedQty(item.getRejectedQty().add(qty));
            item.setDowngradedQty(BigDecimal.ZERO);
            item.setResult(item.getAcceptedQty().signum() > 0
                    ? AcceptanceItemResult.PARTIAL : AcceptanceItemResult.REJECTED);
            returnService.createInternal(supplier, item, ingredient, qty,
                    "降级审批驳回转退货", qty.multiply(price));
        }
        return itemRepo.save(item);
    }

    public AcceptanceRecord get(Long id) {
        return recordRepo.findById(id).orElseThrow(() -> BusinessException.notFound("验收记录不存在: " + id));
    }

    public RecordDetail detail(Long id) {
        return new RecordDetail(get(id), itemRepo.findByRecordId(id));
    }

    public List<AcceptanceItem> itemsOfRecord(Long recordId) {
        return itemRepo.findByRecordId(recordId);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String toCsv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String csv = String.join(",", values.stream().filter(v -> v != null && !v.isBlank()).toList());
        return csv.isBlank() ? null : csv;
    }

    public record RecordDetail(AcceptanceRecord record, List<AcceptanceItem> items) {
    }
}
