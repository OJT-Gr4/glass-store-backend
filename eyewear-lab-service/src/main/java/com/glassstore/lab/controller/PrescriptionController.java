package com.glassstore.lab.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.repository.PrescriptionRepository;
import com.glassstore.lab.dto.CreatePrescriptionRequest;
import com.glassstore.lab.dto.UpdatePrescriptionRequest;
import com.glassstore.lab.entity.PrescriptionRevision;
import com.glassstore.lab.service.PrescriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/api/prescriptions", "/api/v1/prescriptions"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class PrescriptionController {

    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionService prescriptionService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Prescription>>> getAllPrescriptions() {
        List<Prescription> list = prescriptionRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách toa kính thành công", list));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Prescription>> getPrescriptionById(@PathVariable("id") Long id) {
        Prescription p = prescriptionService.get(id);
        return ResponseEntity.ok(ApiResponse.success("Lấy chi tiết toa kính thành công", p));
    }

    @GetMapping("/{id}/revisions")
    public ResponseEntity<ApiResponse<List<PrescriptionRevision>>> history(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success("Prescription revision history", prescriptionService.history(id)));
    }

    @GetMapping("/{id}/revisions/{version}")
    public ResponseEntity<ApiResponse<PrescriptionRevision>> revision(@PathVariable("id") Long id,
                                                                     @PathVariable("version") Long version) {
        return ResponseEntity.ok(ApiResponse.success("Immutable prescription revision", prescriptionService.revision(id, version)));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<List<Prescription>>> getByCustomer(@PathVariable("customerId") Long customerId) {
        com.glassstore.common.security.CurrentActor.requireOwner(customerId);
        List<Prescription> list = prescriptionRepository.findByCustomerIdOrderByCreatedAtDescIdDesc(customerId);
        return ResponseEntity.ok(ApiResponse.success("Lấy lịch sử khúc xạ của khách hàng thành công", list));
    }

    /** Exposes the DTO constraints so clients use the backend's measurement domains. */
    @GetMapping("/validation-rules")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> validationRules() {
        java.util.Map<String, Object> rules = new java.util.LinkedHashMap<>();
        for (var field : com.glassstore.lab.dto.CreatePrescriptionRequest.class.getDeclaredFields()) {
            java.util.Map<String, Object> rule = new java.util.LinkedHashMap<>();
            rule.put("required", field.isAnnotationPresent(jakarta.validation.constraints.NotNull.class));
            var min = field.getAnnotation(jakarta.validation.constraints.DecimalMin.class);
            var max = field.getAnnotation(jakarta.validation.constraints.DecimalMax.class);
            var intMin = field.getAnnotation(jakarta.validation.constraints.Min.class);
            var intMax = field.getAnnotation(jakarta.validation.constraints.Max.class);
            var digits = field.getAnnotation(jakarta.validation.constraints.Digits.class);
            if (min != null) rule.put("min", min.value());
            if (max != null) rule.put("max", max.value());
            if (intMin != null) rule.put("min", intMin.value());
            if (intMax != null) rule.put("max", intMax.value());
            if (field.getType() == Integer.class) rule.put("integer", true);
            if (digits != null) rule.put("fraction", digits.fraction());
            rules.put(field.getName(), rule);
        }
        return ResponseEntity.ok(ApiResponse.success("Prescription validation rules", rules));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Prescription>> createPrescription(
            @Valid @RequestBody CreatePrescriptionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Prescription saved", prescriptionService.create(request)));
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public ResponseEntity<ApiResponse<Prescription>> updatePrescription(@PathVariable("id") Long id,
            @Valid @RequestBody UpdatePrescriptionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Prescription revision saved", prescriptionService.update(id, request)));
    }
}
