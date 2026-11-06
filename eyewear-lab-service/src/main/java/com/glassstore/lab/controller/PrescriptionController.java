package com.glassstore.lab.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.lab.entity.Prescription;
import com.glassstore.lab.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping({"/api/prescriptions", "/api/v1/prescriptions"})
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class PrescriptionController {

    private final PrescriptionRepository prescriptionRepository;
    private final com.glassstore.lab.client.PrescriptionCustomerClient customerClient;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Prescription>>> getAllPrescriptions() {
        List<Prescription> list = prescriptionRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách toa kính thành công", list));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Prescription>> getPrescriptionById(@PathVariable("id") Long id) {
        Prescription p = prescriptionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy toa kính với ID: " + id));
        com.glassstore.common.security.CurrentActor.requireOwner(p.getCustomerId());
        return ResponseEntity.ok(ApiResponse.success("Lấy chi tiết toa kính thành công", p));
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
            @jakarta.validation.Valid @RequestBody com.glassstore.lab.dto.CreatePrescriptionRequest request) {
        String customerName = customerClient.requireCustomer(request.getCustomerId());
        Prescription prescription = new Prescription();
        prescription.setCustomerId(request.getCustomerId());
        prescription.setOdSph(request.getOdSph());
        prescription.setOdCyl(request.getOdCyl());
        prescription.setOdAxis(request.getOdAxis());
        prescription.setOdAdd(request.getOdAdd());
        prescription.setOsSph(request.getOsSph());
        prescription.setOsCyl(request.getOsCyl());
        prescription.setOsAxis(request.getOsAxis());
        prescription.setOsAdd(request.getOsAdd());
        prescription.setPd(request.getPd());
        prescription.setNotes(request.getNotes());
        prescription.setLensSku(request.getLensSku());
        prescription.setLensName(request.getLensName());
        prescription.setCustomerName(customerName);
        prescription.setOptometristId(com.glassstore.common.security.CurrentActor.id());
        prescription.setOptometristName(com.glassstore.common.security.CurrentActor.name());
        prescription.setCreatedAt(LocalDateTime.now());
        return ResponseEntity.ok(ApiResponse.success("Prescription saved", prescriptionRepository.save(prescription)));
    }
}
