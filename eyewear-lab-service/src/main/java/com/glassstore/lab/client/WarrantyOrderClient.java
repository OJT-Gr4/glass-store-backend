package com.glassstore.lab.client;

import com.glassstore.lab.dto.WarrantyOrderItemDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(configuration = ServiceClientSecurity.class, name = "order-pos-warranty", url = "${services.order-pos-service.url}")
public interface WarrantyOrderClient {

    @GetMapping("/internal/warranty-items")
    List<WarrantyOrderItemDTO> findPurchases(
            @RequestParam(name = "phone", required = false) String phone,
            @RequestParam(name = "sku", required = false) String sku);

    @GetMapping("/internal/warranty-items/{orderId}/{orderItemId}")
    WarrantyOrderItemDTO getPurchaseItem(
            @PathVariable("orderId") Long orderId,
            @PathVariable("orderItemId") Long orderItemId);
}
