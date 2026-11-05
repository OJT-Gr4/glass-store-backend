package com.glassstore.lab.controller;

import com.glassstore.lab.dto.WarrantyActivationRequest;
import com.glassstore.lab.dto.WarrantyLookupItemDTO;
import com.glassstore.lab.entity.Warranty;
import com.glassstore.lab.repository.WarrantyRepository;
import com.glassstore.lab.service.WarrantyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        return ResponseEntity.ok(warrantyService.activate(request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Warranty> updateStatus(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body) {
        return warrantyRepository.findById(id).map(w -> {
            if (body.containsKey("status")) w.setStatus(body.get("status"));
            if (body.containsKey("statusLabel")) w.setStatusLabel(body.get("statusLabel"));
            if (body.containsKey("statusColor")) w.setStatusColor(body.get("statusColor"));
            if (body.containsKey("techNote")) w.setTechNote(body.get("techNote"));
            w.setUpdatedAt(LocalDateTime.now());
            return ResponseEntity.ok(warrantyRepository.save(w));
        }).orElse(ResponseEntity.notFound().build());
    }
}
