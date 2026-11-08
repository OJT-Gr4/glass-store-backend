package com.glassstore.lab.controller;

import com.glassstore.lab.dto.WarrantyActivationRequest;
import com.glassstore.lab.dto.WarrantyLookupItemDTO;
import com.glassstore.lab.entity.Warranty;
import com.glassstore.lab.entity.WarrantyClaimHistory;
import com.glassstore.lab.entity.WarrantyPolicy;
import com.glassstore.lab.repository.WarrantyClaimHistoryRepository;
import com.glassstore.lab.repository.WarrantyRepository;
import com.glassstore.lab.service.WarrantyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/warranties")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class WarrantyController {

    private final WarrantyRepository warrantyRepository;
    private final WarrantyService warrantyService;
    private final WarrantyClaimHistoryRepository claimHistoryRepository;

    private List<Warranty> visible(List<Warranty> rows) {
        return com.glassstore.common.security.CurrentActor.customer() ? rows.stream().filter(w -> java.util.Objects.equals(w.getCustomerId(),com.glassstore.common.security.CurrentActor.id())).toList() : rows;
    }

    @GetMapping
    public ResponseEntity<List<Warranty>> getAllWarranties() {
        return ResponseEntity.ok(visible(warrantyRepository.findAllByOrderByCreatedAtDesc()));
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchWarranties(
            @RequestParam(name = "phone", required = false) String phone,
            @RequestParam(name = "barcode", required = false) String barcode,
            @RequestParam(name = "q", required = false) String q) {
        if (com.glassstore.common.security.CurrentActor.customer() && ((phone != null && !phone.isBlank()) || (barcode != null && !barcode.isBlank()))) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        if (phone != null && !phone.isBlank()) {
            return ResponseEntity.ok(warrantyService.lookupByPhone(phone));
        }
        if (barcode != null && !barcode.isBlank()) {
            return ResponseEntity.ok(warrantyService.lookupByBarcode(barcode));
        }
        if (q != null && !q.trim().isEmpty()) {
            return ResponseEntity.ok(visible(warrantyRepository.search(q.trim())));
        }
        return ResponseEntity.ok(warrantyRepository.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Warranty> getWarrantyById(@PathVariable("id") Long id) {
        return warrantyRepository.findById(id)
                .map(w -> { com.glassstore.common.security.CurrentActor.requireOwner(w.getCustomerId()); return ResponseEntity.ok(w); })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<Warranty> getWarrantyByCode(@PathVariable("code") String code) {
        return warrantyRepository.findByWarrantyCode(code)
                .map(w -> { com.glassstore.common.security.CurrentActor.requireOwner(w.getCustomerId()); return ResponseEntity.ok(w); })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Warranty> createWarranty(@RequestBody Warranty warranty) {
        warranty.setId(null);
        if (warranty.getWarrantyCode() == null || warranty.getWarrantyCode().isEmpty()) {
            warranty.setWarrantyCode("#WAR-" + (System.currentTimeMillis() % 10000));
        }
        if (warranty.getStatus() == null || warranty.getStatus().isEmpty()) {
            warranty.setStatus("WAITING_PROCESS");
        }
        if (warranty.getStatusLabel() == null || warranty.getStatusLabel().isEmpty()) {
            warranty.setStatusLabel("Chờ tiếp nhận");
        }
        if (warranty.getStatusColor() == null || warranty.getStatusColor().isEmpty()) {
            warranty.setStatusColor("#f59e0b");
        }
        warranty.setCreatedAt(LocalDateTime.now());
        warranty.setUpdatedAt(LocalDateTime.now());
        Warranty saved = warrantyRepository.save(warranty);
        log.info("Tạo mới phiếu bảo hành thành công: {}", saved.getWarrantyCode());
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/activate")
    public ResponseEntity<Warranty> activateWarranty(@Valid @RequestBody WarrantyActivationRequest request) {
        Warranty activated = warrantyService.activate(request);
        if (claimHistoryRepository != null && activated.getId() != null
                && claimHistoryRepository.findByWarrantyIdOrderByChangedAtAsc(activated.getId()).isEmpty()) {
            claimHistoryRepository.save(WarrantyClaimHistory.builder()
                    .warrantyId(activated.getId())
                    .fromStatus(null)
                    .toStatus(activated.getStatus())
                    .changedBy(com.glassstore.common.security.CurrentActor.name())
                    .note("Kích hoạt bảo hành theo chính sách " + activated.getPolicyRef())
                    .changedAt(LocalDateTime.now())
                    .build());
        }
        return ResponseEntity.ok(activated);
    }

    @GetMapping("/policies")
    public ResponseEntity<List<WarrantyPolicy>> getPolicies() {
        return ResponseEntity.ok(warrantyService.getActivePolicies());
    }

    @PutMapping("/policies/{benefitType}")
    public ResponseEntity<WarrantyPolicy> updatePolicy(
            @PathVariable("benefitType") String benefitType,
            @RequestBody Map<String, Object> body) {
        if (!com.glassstore.common.security.CurrentActor.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ Quản trị viên (Admin) được cấu hình chính sách bảo hành");
        }
        Integer durationDays = body.get("durationDays") == null || body.get("durationDays").toString().isBlank()
                ? null
                : Integer.valueOf(body.get("durationDays").toString());
        String coverageTerms = body.get("coverageTerms") == null ? null : body.get("coverageTerms").toString();
        BigDecimal serviceFee = body.get("serviceFee") == null || body.get("serviceFee").toString().isBlank()
                ? BigDecimal.ZERO
                : new BigDecimal(body.get("serviceFee").toString());
        return ResponseEntity.ok(warrantyService.savePolicyVersion(
                benefitType,
                durationDays,
                coverageTerms,
                serviceFee,
                com.glassstore.common.security.CurrentActor.name()));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<WarrantyClaimHistory>> getClaimHistory(@PathVariable("id") Long id) {
        Warranty warranty = warrantyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        com.glassstore.common.security.CurrentActor.requireOwner(warranty.getCustomerId());
        return ResponseEntity.ok(claimHistoryRepository.findByWarrantyIdOrderByChangedAtAsc(id));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Warranty> updateStatus(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body) {
        return warrantyRepository.findById(id).map(w -> {
            String previousStatus = w.getStatus();
            if (body.containsKey("status")) w.setStatus(body.get("status"));
            if (body.containsKey("statusLabel")) w.setStatusLabel(body.get("statusLabel"));
            if (body.containsKey("statusColor")) w.setStatusColor(body.get("statusColor"));
            if (body.containsKey("techNote")) w.setTechNote(body.get("techNote"));
            w.setUpdatedAt(LocalDateTime.now());
            Warranty saved = warrantyRepository.save(w);
            if (claimHistoryRepository != null) {
                claimHistoryRepository.save(WarrantyClaimHistory.builder()
                        .warrantyId(saved.getId())
                        .fromStatus(previousStatus)
                        .toStatus(saved.getStatus())
                        .changedBy(com.glassstore.common.security.CurrentActor.name())
                        .note(body.getOrDefault("techNote", "Cập nhật trạng thái bảo hành"))
                        .changedAt(LocalDateTime.now())
                        .build());
            }
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }
}
