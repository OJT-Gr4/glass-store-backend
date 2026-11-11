package com.glassstore.catalog.controller;

import com.glassstore.catalog.dto.CreateStockTransferRequest;
import com.glassstore.catalog.dto.InventoryLocationDTO;
import com.glassstore.catalog.dto.InventoryMovementDTO;
import com.glassstore.catalog.dto.InventoryReconciliationDTO;
import com.glassstore.catalog.dto.InventoryRowDTO;
import com.glassstore.catalog.dto.StockDeductionRequest;
import com.glassstore.catalog.dto.StockDeductionResponse;
import com.glassstore.catalog.dto.StockTransferDTO;
import com.glassstore.catalog.dto.StockDtos;
import com.glassstore.catalog.dto.StockCheckRequest;
import com.glassstore.catalog.dto.VariantInfoDTO;
import com.glassstore.catalog.service.InventoryQueryService;
import com.glassstore.catalog.service.InventoryReconciliationService;
import com.glassstore.catalog.service.InventoryService;
import com.glassstore.catalog.service.StockService;
import com.glassstore.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Internal endpoints consumed by order-pos-service over Feign.
 *
 * <p>These are not exposed to the public: the gateway does not route {@code /api/variants},
 * and they exist so stock mutations happen inside catalog-service where the data lives.</p>
 */
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class InventoryController {

    @PostMapping("/internal/inventory/sales/check")
    public ResponseEntity<Void> checkAvailability(@Valid @RequestBody StockCheckRequest request) {
        inventoryService.checkAvailability(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/inventory/sales/cancel")
    public ResponseEntity<Void> cancelSale(@Valid @RequestBody StockDeductionRequest request) {
        inventoryService.cancelSale(request);
        return ResponseEntity.noContent().build();
    }

    private final InventoryQueryService queryService;
    private final StockService stockService;
    private final InventoryService inventoryService;
    private final InventoryReconciliationService reconciliationService;

    @GetMapping("/api/variants/sku/{sku}")
    public ResponseEntity<ApiResponse<VariantInfoDTO>> getBySku(@PathVariable("sku") String sku) {
        return ResponseEntity.ok(ApiResponse.success("OK", queryService.getBySku(sku)));
    }

    @GetMapping("/api/variants/barcode/{barcode}")
    public ResponseEntity<ApiResponse<VariantInfoDTO>> getByBarcode(@PathVariable("barcode") String barcode) {
        return ResponseEntity.ok(ApiResponse.success("OK", queryService.getByBarcode(barcode)));
    }

    @GetMapping("/api/variants/search")
    public ResponseEntity<ApiResponse<List<VariantInfoDTO>>> search(
            @RequestParam(name = "q", required = false) String keyword) {
        return ResponseEntity.ok(ApiResponse.success("OK", queryService.search(keyword)));
    }

    @PostMapping("/api/variants/reserve")
    public ResponseEntity<ApiResponse<StockDtos.ReservationResult>> reserve(
            @Valid @RequestBody StockDtos.ReserveRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Giữ tồn kho thành công", stockService.reserve(request)));
    }

    @PostMapping("/api/variants/release")
    public ResponseEntity<ApiResponse<StockDtos.ReservationResult>> release(
            @Valid @RequestBody StockDtos.ReleaseRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Hủy giữ tồn kho thành công", stockService.release(request)));
    }

    @PostMapping("/api/variants/commit")
    public ResponseEntity<ApiResponse<StockDtos.ReservationResult>> commit(
            @Valid @RequestBody StockDtos.CommitRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Xuất kho thành công", stockService.commit(request)));
    }

    /** Manual stock correction. Requires a reason (BR-12). */
    @PostMapping("/api/variants/adjust")
    public ResponseEntity<ApiResponse<Void>> adjust(@Valid @RequestBody StockDtos.AdjustRequest request) {
        stockService.adjust(request.getSku(), request.getDelta(), com.glassstore.common.security.CurrentActor.name(), request.getReason());
        return ResponseEntity.ok(ApiResponse.success("Điều chỉnh tồn kho thành công", null));
    }

    @GetMapping("/api/inventory")
    public ResponseEntity<ApiResponse<List<InventoryRowDTO>>> getInventory() {
        return ResponseEntity.ok(ApiResponse.success("Lấy tồn kho theo địa điểm thành công", inventoryService.getInventory()));
    }

    @GetMapping("/api/inventory/reconciliation")
    public ResponseEntity<ApiResponse<InventoryReconciliationDTO>> reconcileInventory() {
        return ResponseEntity.ok(ApiResponse.success(
                "Đối soát tồn kho hoàn tất", reconciliationService.reconcile()));
    }

    @GetMapping("/api/inventory/locations")
    public ResponseEntity<ApiResponse<List<InventoryLocationDTO>>> getLocations() {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách kho thành công", inventoryService.getLocations()));
    }

    @GetMapping("/api/inventory/transfers")
    public ResponseEntity<ApiResponse<List<StockTransferDTO>>> getTransfers() {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách phiếu điều chuyển thành công", inventoryService.getTransfers()));
    }

    @GetMapping("/api/inventory/movements")
    public ResponseEntity<ApiResponse<Page<InventoryMovementDTO>>> getMovements(
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String locationCode,
            @RequestParam(required = false) String referenceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success("Lấy lịch sử biến động kho thành công",
                inventoryService.getMovements(sku, locationCode, referenceId, page, size)));
    }

    @PostMapping("/api/inventory/transfers")
    public ResponseEntity<ApiResponse<StockTransferDTO>> createTransfer(
            @Valid @RequestBody CreateStockTransferRequest request,
            @AuthenticationPrincipal Jwt principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo phiếu điều chuyển thành công",
                        inventoryService.createTransfer(request, actorFrom(principal))));
    }

    @PatchMapping("/api/inventory/transfers/{id}/dispatch")
    public ResponseEntity<ApiResponse<StockTransferDTO>> dispatchTransfer(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal Jwt principal) {
        return ResponseEntity.ok(ApiResponse.success("Xuất kho điều chuyển thành công",
                inventoryService.dispatchTransfer(id, actorFrom(principal))));
    }

    @PatchMapping("/api/inventory/transfers/{id}/receive")
    public ResponseEntity<ApiResponse<StockTransferDTO>> receiveTransfer(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal Jwt principal) {
        return ResponseEntity.ok(ApiResponse.success("Xác nhận nhận hàng thành công",
                inventoryService.receiveTransfer(id, actorFrom(principal))));
    }

    @PostMapping("/internal/inventory/sales/deduct")
    public ResponseEntity<StockDeductionResponse> deductPaidOrder(
            @Valid @RequestBody StockDeductionRequest request) {
        return ResponseEntity.ok(inventoryService.deductPaidOrder(request));
    }

    private String actorFrom(Jwt principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Yêu cầu xác thực nhân viên.");
        }
        String username = principal.getClaimAsString("username");
        if (username != null && !username.isBlank()) {
            return username;
        }
        String subject = principal.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token nhân viên không có định danh hợp lệ.");
        }
        return subject;
    }
}
