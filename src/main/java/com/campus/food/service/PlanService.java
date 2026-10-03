package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 采购计划与订货单服务 */
@Service
@RequiredArgsConstructor
public class PlanService {

    private final WeeklyMenuRepo menuRepo;
    private final MenuMealRepo mealRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final PurchasePlanRepo planRepo;
    private final PurchasePlanItemRepo planItemRepo;
    private final PurchaseOrderRepo orderRepo;
    private final OrderItemRepo orderItemRepo;
    private final SupplierRepo supplierRepo;
    private final InventoryService inventoryService;

    /**
     * 由周菜单生成采购计划：汇总各餐次食材需求，扣除可用库存（保留安全线）后得出计划采购量。
     */
    @Transactional
    public PurchasePlan generatePlan(Long menuId, String note) {
        WeeklyMenu menu = menuRepo.findById(menuId)
                .orElseThrow(() -> BusinessException.notFound("菜单不存在: " + menuId));
        if (menu.getStatus() != MenuStatus.PUBLISHED) {
            throw BusinessException.conflict("菜单未发布，不能生成采购计划");
        }

        // 汇总未取消餐次的食材需求
        Map<Long, BigDecimal> required = new LinkedHashMap<>();
        Map<Long, Ingredient> ingredients = new LinkedHashMap<>();
        for (MenuMeal meal : mealRepo.findByMenuIdOrderByDateAsc(menuId)) {
            if (meal.getStatus() == MealStatus.CANCELLED) {
                continue;
            }
            for (MenuMealIngredient mmi : mealIngredientRepo.findByMealId(meal.getId())) {
                Ingredient ing = mmi.getIngredient();
                ingredients.put(ing.getId(), ing);
                required.merge(ing.getId(), mmi.getQuantity(), BigDecimal::add);
            }
        }
        if (required.isEmpty()) {
            throw BusinessException.badRequest("菜单没有食材需求，无法生成采购计划");
        }

        List<Supplier> activeSuppliers = supplierRepo.findAll().stream().filter(Supplier::isActive).toList();

        PurchasePlan plan = new PurchasePlan();
        plan.setPlanNo(NoGen.next("PL"));
        plan.setMenu(menu);
        plan.setNote(note);
        planRepo.save(plan);

        for (Map.Entry<Long, BigDecimal> e : required.entrySet()) {
            Ingredient ing = ingredients.get(e.getKey());
            BigDecimal requiredQty = e.getValue();
            BigDecimal stock = inventoryService.currentQty(ing.getId());
            // 可用库存 = 当前库存 - 安全线（不为负）
            BigDecimal usable = stock.subtract(ing.getSafetyStock()).max(BigDecimal.ZERO);
            BigDecimal plannedQty = requiredQty.subtract(usable).max(BigDecimal.ZERO);
            if (plannedQty.signum() <= 0) {
                continue; // 库存充足，无需采购
            }
            PurchasePlanItem item = new PurchasePlanItem();
            item.setPlan(plan);
            item.setIngredient(ing);
            item.setRequiredQty(requiredQty);
            item.setStockQty(stock);
            item.setPlannedQty(plannedQty);
            item.setEstimatedPrice(ing.getReferencePrice());
            Supplier matched = suggestSupplier(activeSuppliers, ing.getCategory());
            item.setSupplier(matched);
            // 供应商被考核"降低下月采购比例"时，按比例缩减分配给该供应商的计划采购量
            if (matched != null && matched.getPurchaseRatio() != null
                    && matched.getPurchaseRatio().compareTo(BigDecimal.ONE) != 0) {
                item.applyPurchaseRatio(matched.getPurchaseRatio());
                if (item.getPlannedQty().signum() <= 0) {
                    continue; // 比例缩减后采购量为 0，跳过（不下单）
                }
            } else {
                item.setOriginalPlannedQty(plannedQty);
            }
            planItemRepo.save(item);
        }
        return plan;
    }

    private Supplier suggestSupplier(List<Supplier> suppliers, IngredientCategory category) {
        // 品类匹配即可；冷链能力在生成订货单时校验
        return suppliers.stream()
                .filter(s -> s.getCategories() != null && s.getCategories().contains(category.name()))
                .findFirst()
                .orElse(null);
    }

    public PurchasePlan getPlan(Long id) {
        return planRepo.findById(id).orElseThrow(() -> BusinessException.notFound("采购计划不存在: " + id));
    }

    public List<PurchasePlan> listPlans() {
        return planRepo.findAll();
    }

    public PlanDetail detail(Long id) {
        return new PlanDetail(getPlan(id), planItemRepo.findByPlanId(id));
    }

    /**
     * 按计划按供应商拆分生成订货单。
     */
    @Transactional
    public List<PurchaseOrder> createOrders(Long planId, LocalDateTime expectedArrivalAt, String remark) {
        PurchasePlan plan = getPlan(planId);
        if (plan.getStatus() != PlanStatus.DRAFT) {
            throw BusinessException.conflict("计划已下单或已关闭");
        }
        if (expectedArrivalAt == null) {
            throw BusinessException.badRequest("必须提供要求到货时间 expectedArrivalAt");
        }
        List<PurchasePlanItem> items = planItemRepo.findByPlanId(planId);
        Map<Supplier, List<PurchasePlanItem>> bySupplier = new LinkedHashMap<>();
        for (PurchasePlanItem item : items) {
            if (item.getSupplier() == null || item.getPlannedQty().signum() <= 0) {
                continue;
            }
            bySupplier.computeIfAbsent(item.getSupplier(), k -> new ArrayList<>()).add(item);
        }
        if (bySupplier.isEmpty()) {
            throw BusinessException.badRequest("计划中没有可下单的明细（缺少供应商或采购量为 0）");
        }

        List<PurchaseOrder> orders = new ArrayList<>();
        for (Map.Entry<Supplier, List<PurchasePlanItem>> e : bySupplier.entrySet()) {
            Supplier supplier = e.getKey();
            PurchaseOrder order = new PurchaseOrder();
            order.setOrderNo(NoGen.next("PO"));
            order.setPlan(plan);
            order.setSupplier(supplier);
            order.setExpectedArrivalAt(expectedArrivalAt);
            order.setRemark(remark);
            orderRepo.save(order);

            for (PurchasePlanItem pi : e.getValue()) {
                Ingredient ing = pi.getIngredient();
                if (ing.isRequiresColdChain() && !supplier.isColdChainCapable()) {
                    throw BusinessException.badRequest("供应商[" + supplier.getName() + "]不具备冷链能力，不能订购冷链食材[" + ing.getName() + "]");
                }
                OrderItem oi = new OrderItem();
                oi.setOrder(order);
                oi.setIngredient(ing);
                oi.setSpec(ing.getSpec());
                oi.setQuantity(pi.getPlannedQty());
                oi.setContractPrice(pi.getEstimatedPrice());
                oi.setRequiresColdChain(ing.isRequiresColdChain());
                oi.setQualityRequirements(defaultQualityRequirement(ing.getCategory()));
                orderItemRepo.save(oi);
            }
            orders.add(order);
        }
        plan.setStatus(PlanStatus.ORDERED);
        planRepo.save(plan);
        return orders;
    }

    private String defaultQualityRequirement(IngredientCategory category) {
        return switch (category) {
            case MEAT -> "需提供动物检疫合格证明，肉质新鲜无异味，冷链运输";
            case VEGETABLE -> "新鲜无腐烂，腐烂比例不超过10%，农残抽检合格";
            case FRUIT -> "无霉变无碰伤，腐烂比例不超过10%";
            case DAIRY -> "在保质期内，冷链运输，包装完好";
            case GRAIN_OIL -> "生产日期在保质期内前1/3，包装完好无虫蛀";
            case CONDIMENT -> "包装完好，在保质期内";
        };
    }

    public record PlanDetail(PurchasePlan plan, List<PurchasePlanItem> items) {
    }
}
