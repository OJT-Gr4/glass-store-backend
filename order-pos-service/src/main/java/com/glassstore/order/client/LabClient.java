package com.glassstore.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Creates lens fabrication jobs in eyewear-lab-service (FR-POS-16). */
@FeignClient(configuration = ServiceClientSecurity.class, name = "eyewear-lab-service", contextId = "labClient", url = "${services.eyewear-lab-service.url}")
public interface LabClient {

    @GetMapping("/api/lab-jobs/by-order/{orderId}")
    CatalogContracts.ApiEnvelope<Map<String,Object>> getByOrder(@PathVariable("orderId") Long orderId);

    @GetMapping("/api/prescriptions/{id}")
    CatalogContracts.ApiEnvelope<Map<String,Object>> getPrescription(@PathVariable("id") Long id);

    @PostMapping("/api/lab-jobs/from-order")
    CatalogContracts.ApiEnvelope<Map<String, Object>> createFromOrder(@RequestBody Map<String, Object> body);
}
