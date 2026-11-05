package com.glassstore.lab.controller;

import com.glassstore.lab.dto.RepairServiceCreateRequest;
import com.glassstore.lab.dto.RepairServiceResponse;
import com.glassstore.lab.dto.RepairServiceStatusRequest;
import com.glassstore.lab.service.RepairServiceWorkflow;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/repair-services")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RepairServiceController {

    private final RepairServiceWorkflow repairServiceWorkflow;

    @GetMapping
    public ResponseEntity<List<RepairServiceResponse>> getRepairServices(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "phone", required = false) String phone,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(repairServiceWorkflow.search(code, phone, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RepairServiceResponse> getRepairService(@PathVariable("id") Long id) {
        return ResponseEntity.ok(repairServiceWorkflow.get(id));
    }

    @PostMapping
    public ResponseEntity<RepairServiceResponse> createRepairService(
            @Valid @RequestBody RepairServiceCreateRequest request) {
        request = new RepairServiceCreateRequest(request.customerName(),request.customerPhone(),request.productName(),
            request.productType(),request.productBarcode(),request.frameCondition(),request.lensCondition(),request.reportedProblem(),
            request.physicalCondition(),request.requestedServices(),request.assignedTechnician(),
            com.glassstore.common.security.CurrentActor.name(),request.expectedPickupAt(),request.notes(),request.customerNotes());
        return ResponseEntity.status(HttpStatus.CREATED).body(repairServiceWorkflow.create(request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<RepairServiceResponse> updateStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody RepairServiceStatusRequest request) {
        request = new RepairServiceStatusRequest(request.status(),com.glassstore.common.security.CurrentActor.name(),request.notes());
        return ResponseEntity.ok(repairServiceWorkflow.updateStatus(id, request));
    }
}
