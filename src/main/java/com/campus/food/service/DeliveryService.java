package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.AnomalyType;
import com.campus.food.domain.enums.OrderStatus;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 送货批次服务：登记到货并自动识别晚送/冷链/替换异常 */
@Service
@RequiredArgsConstructor
public class DeliveryService {

    /** 晚送宽限分钟数 */
    private static final long LATE_GRACE_MINUTES = 30;

    private final DeliveryBatchRepo batchRepo;
    private final DeliveryItemRepo deliveryItemRepo;
    private final PurchaseOrderRepo orderRepo;
    private final OrderItemRepo orderItemRepo;
    private final IngredientRepo ingredientRepo;
    private final AnomalyService anomalyService;

    public record DeliveryItemInput(Long orderItemId, Long ingredientId, BigDecimal deliveredQty,
                                    String productionBatchNo, java.time.LocalDate productionDate,
                                    java.time.LocalDate expiryDate, String quarantineCertNo,
                                    String qualityCertNo) {
    }

    /**
     * 登记送货批次。
     */
    @Transactional
    public DeliveryBatch registerBatch(Long orderId, String licensePlate, String driverName, String driverPhone,
                                       LocalDateTime arrivedAt, BigDecimal vehicleTemperature,
                                       List<DeliveryItemInput> items) {
        PurchaseOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> BusinessException.notFound("订货单不存在: " + orderId));
        if (order.getStatus() != OrderStatus.PLACED && order.getStatus() != OrderStatus.DELIVERED
                && order.getStatus() != OrderStatus.ACCEPTED) {
            throw BusinessException.conflict("订货单状态不允许登记到货: " + order.getStatus());
        }
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("送货明细不能为空");
        }
        if (licensePlate == null || licensePlate.isBlank()) {
            throw BusinessException.badRequest("必须登记车牌号");
        }

        DeliveryBatch batch = new DeliveryBatch();
        batch.setBatchNo(NoGen.next("DB"));
        batch.setOrder(order);
        batch.setLicensePlate(licensePlate);
        batch.setDriverName(driverName);
        batch.setDriverPhone(driverPhone);
        batch.setArrivedAt(arrivedAt == null ? LocalDateTime.now() : arrivedAt);
        batch.setVehicleTemperature(vehicleTemperature);
        batchRepo.save(batch);

        // 晚送判定
        if (batch.getArrivedAt().isAfter(order.getExpectedArrivalAt().plusMinutes(LATE_GRACE_MINUTES))) {
            BigDecimal orderAmount = orderItemRepo.findByOrderId(order.getId()).stream()
                    .map(oi -> oi.getContractPrice().multiply(oi.getQuantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            anomalyService.create(order.getSupplier(), order, batch, AnomalyType.LATE_DELIVERY, null,
                    "要求 " + order.getExpectedArrivalAt() + " 前到货，实际 " + batch.getArrivedAt() + " 到校",
                    orderAmount);
        }

        // 冷链温度判定（按订单内冷链食材的温度区间）
        List<OrderItem> orderItems = orderItemRepo.findByOrderId(order.getId());
        if (vehicleTemperature != null) {
            BigDecimal coldValue = BigDecimal.ZERO;
            boolean breach = false;
            StringBuilder sb = new StringBuilder();
            for (OrderItem oi : orderItems) {
                Ingredient ing = oi.getIngredient();
                if (!ing.isRequiresColdChain()) {
                    continue;
                }
                boolean tooHigh = ing.getColdChainMaxTemp() != null
                        && vehicleTemperature.compareTo(ing.getColdChainMaxTemp()) > 0;
                boolean tooLow = ing.getColdChainMinTemp() != null
                        && vehicleTemperature.compareTo(ing.getColdChainMinTemp()) < 0;
                if (tooHigh || tooLow) {
                    breach = true;
                    coldValue = coldValue.add(oi.getContractPrice().multiply(oi.getQuantity()));
                    sb.append(ing.getName()).append("要求")
                            .append(ing.getColdChainMinTemp()).append("~")
                            .append(ing.getColdChainMaxTemp()).append("℃ ");
                }
            }
            if (breach) {
                anomalyService.create(order.getSupplier(), order, batch, AnomalyType.COLD_CHAIN_BREACH, null,
                        "车厢实测 " + vehicleTemperature + "℃，" + sb, coldValue);
            }
        }

        // 送货明细 + 替换品类判定
        for (DeliveryItemInput in : items) {
            Ingredient actual = ingredientRepo.findById(in.ingredientId())
                    .orElseThrow(() -> BusinessException.notFound("食材不存在: " + in.ingredientId()));
            OrderItem orderItem = null;
            if (in.orderItemId() != null) {
                orderItem = orderItemRepo.findById(in.orderItemId())
                        .orElseThrow(() -> BusinessException.notFound("订货明细不存在: " + in.orderItemId()));
                if (!orderItem.getOrder().getId().equals(order.getId())) {
                    throw BusinessException.badRequest("订货明细不属于该订货单: " + in.orderItemId());
                }
            }
            if (in.deliveredQty() == null || in.deliveredQty().signum() <= 0) {
                throw BusinessException.badRequest("送货数量必须大于 0");
            }
            DeliveryItem di = new DeliveryItem();
            di.setBatch(batch);
            di.setOrderItem(orderItem);
            di.setIngredient(actual);
            di.setDeliveredQty(in.deliveredQty());
            di.setProductionBatchNo(in.productionBatchNo());
            di.setProductionDate(in.productionDate());
            di.setExpiryDate(in.expiryDate());
            di.setQuarantineCertNo(in.quarantineCertNo());
            di.setQualityCertNo(in.qualityCertNo());
            deliveryItemRepo.save(di);

            boolean substituted = orderItem == null
                    || !orderItem.getIngredient().getId().equals(actual.getId());
            if (substituted) {
                String expected = orderItem == null ? "（计划外送货）" : orderItem.getIngredient().getName();
                anomalyService.create(order.getSupplier(), order, batch, AnomalyType.SUBSTITUTION,
                        actual.getCategory(),
                        "临时替换品类：订购[" + expected + "]，实送[" + actual.getName() + "]",
                        actual.getReferencePrice().multiply(in.deliveredQty()));
            }
        }

        order.setStatus(OrderStatus.DELIVERED);
        orderRepo.save(order);
        return batch;
    }

    public DeliveryBatch getBatch(Long id) {
        return batchRepo.findById(id).orElseThrow(() -> BusinessException.notFound("送货批次不存在: " + id));
    }

    public BatchDetail detail(Long id) {
        return new BatchDetail(getBatch(id), deliveryItemRepo.findByBatchId(id));
    }

    public List<DeliveryBatch> listByOrder(Long orderId) {
        return batchRepo.findByOrderId(orderId);
    }

    public record BatchDetail(DeliveryBatch batch, List<DeliveryItem> items) {
    }
}
