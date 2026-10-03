package com.campus.food.web;

import com.campus.food.domain.DeliveryBatch;
import com.campus.food.service.DeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "送货批次")
@RestController
@RequestMapping("/api/deliveries")
@RequiredArgsConstructor
public class DeliveryController {

    private final DeliveryService deliveryService;

    public record DeliveryItemReq(Long orderItemId, @NotNull Long ingredientId,
                                  @NotNull BigDecimal deliveredQty, String productionBatchNo,
                                  LocalDate productionDate, LocalDate expiryDate,
                                  String quarantineCertNo, String qualityCertNo) {
    }

    public record RegisterBatchReq(@NotNull Long orderId, @NotNull String licensePlate,
                                   String driverName, String driverPhone,
                                   LocalDateTime arrivedAt, BigDecimal vehicleTemperature,
                                   @NotEmpty List<DeliveryItemReq> items) {
    }

    @Operation(summary = "登记送货批次（自动识别晚送/冷链超标/替换品类异常；蔬果可登记农残质检证明）")
    @PostMapping
    @PreAuthorize("hasRole('PROCUREMENT')")
    public DeliveryBatch register(@Valid @RequestBody RegisterBatchReq req) {
        List<DeliveryService.DeliveryItemInput> items = req.items().stream()
                .map(i -> new DeliveryService.DeliveryItemInput(i.orderItemId(), i.ingredientId(),
                        i.deliveredQty(), i.productionBatchNo(), i.productionDate(), i.expiryDate(),
                        i.quarantineCertNo(), i.qualityCertNo()))
                .toList();
        return deliveryService.registerBatch(req.orderId(), req.licensePlate(), req.driverName(),
                req.driverPhone(), req.arrivedAt(), req.vehicleTemperature(), items);
    }

    @Operation(summary = "批次详情（含送货明细）")
    @GetMapping("/{id}")
    public DeliveryService.BatchDetail detail(@PathVariable Long id) {
        return deliveryService.detail(id);
    }

    @Operation(summary = "按订货单查询批次")
    @GetMapping
    public List<DeliveryBatch> listByOrder(@RequestParam Long orderId) {
        return deliveryService.listByOrder(orderId);
    }
}
