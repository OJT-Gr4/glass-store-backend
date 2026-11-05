package com.glassstore.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Creates lens fabrication jobs in eyewear-lab-service (FR-POS-16). */
@FeignClient(name = "eyewear-lab-service", contextId = "labClient", url = "${services.eyewear-lab-service.url}")
public interface LabClient {

    @PostMapping("/api/lab-jobs/from-order")
    CatalogContracts.ApiEnvelope<Map<String, Object>> createFromOrder(@RequestBody Map<String, Object> body);
}