package com.glassstore.order.controller;

import com.glassstore.order.dto.WarrantyOrderItemDTO;
import com.glassstore.order.service.WarrantyOrderLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/warranty-items")
@RequiredArgsConstructor
public class WarrantyOrderInternalController {

    private final WarrantyOrderLookupService lookupService;

    @GetMapping
    public ResponseEntity<List<WarrantyOrderItemDTO>> findPurchases(
            @RequestParam(name = "phone", required = false) String phone,
            @RequestParam(name = "sku", required = false) String sku) {
        return ResponseEntity.ok(lookupService.findPurchases(phone, sku));
    }

    @GetMapping("/{orderId}/{orderItemId}")
    public ResponseEntity<WarrantyOrderItemDTO> getPurchaseItem(
            @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId) {
        return ResponseEntity.ok(lookupService.requirePurchaseItem(orderId, orderItemId));
    }
}
