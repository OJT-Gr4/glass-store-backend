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

    @PostMapping
    public ResponseEntity<ApiResponse<Prescription>> createPrescription(@RequestBody Prescription prescription) {
        if (prescription.getCustomerId() == null)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Customer is required");
        prescription.setId(null);
        prescription.setOptometristId(com.glassstore.common.security.CurrentActor.id());
        if (prescription.getOdSph() == null) {
            prescription.setOdSph(BigDecimal.ZERO);
        }
        if (prescription.getOsSph() == null) {
            prescription.setOsSph(BigDecimal.ZERO);
        }
        if (prescription.getOdCyl() == null) {
            prescription.setOdCyl(BigDecimal.ZERO);
        }
        if (prescription.getOsCyl() == null) {
            prescription.setOsCyl(BigDecimal.ZERO);
        }
        if (prescription.getOdAxis() == null) {
            prescription.setOdAxis(0);
        }
        if (prescription.getOsAxis() == null) {
            prescription.setOsAxis(0);
        }
        if (prescription.getPd() == null) {
            prescription.setPd(new BigDecimal("63.0"));
        }
        prescription.setCreatedAt(LocalDateTime.now());
        Prescription saved = prescriptionRepository.save(prescription);
        log.info("Bác sĩ ký duyệt đơn kính thành công: ID = {}, Khách hàng: {}, OD_SPH: {}, OS_SPH: {}, PD: {}",
                saved.getId(), saved.getCustomerName(), saved.getOdSph(), saved.getOsSph(), saved.getPd());
        return ResponseEntity.ok(ApiResponse.success("Ký duyệt và lưu toa kính y khoa thành công", saved));
    }
}
