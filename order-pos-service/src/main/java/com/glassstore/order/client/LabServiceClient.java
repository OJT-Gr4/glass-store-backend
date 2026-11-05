package com.glassstore.order.client;

import com.glassstore.order.dto.CreateLabJobRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * OpenFeign Client gọi sang eyewear-lab-service (Port 8084).
 */
@FeignClient(name = "eyewear-lab-service", contextId = "labServiceClient", url = "${services.eyewear-lab-service.url:http://localhost:8084}")
public interface LabServiceClient {

    @PostMapping("/api/lab-jobs")
    ResponseEntity<?> createLabJob(@RequestBody CreateLabJobRequest request);

    @GetMapping("/api/prescriptions/{id}")
    ResponseEntity<Map<String, Object>> getPrescriptionById(@PathVariable("id") Long id);
}
