package com.glassstore.catalog.controller;

import com.glassstore.catalog.dto.DefectiveInventoryDTO;
import com.glassstore.catalog.dto.StockVoucherDtos;
import com.glassstore.catalog.dto.SupplierDtos;
import com.glassstore.catalog.service.StockVoucherService;
import com.glassstore.catalog.service.SupplierService;
import com.glassstore.common.dto.ApiResponse;
import com.glassstore.common.security.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class StockVoucherController {

    private final StockVoucherService stockVoucherService;
    private final SupplierService supplierService;

    @GetMapping("/api/inventory/vouchers")
    public ResponseEntity<ApiResponse<List<StockVoucherDtos.Response>>> getVouchers() {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách phiếu kho thành công",
                stockVoucherService.getVouchers()));
    }

    @GetMapping("/api/inventory/vouchers/{id}")
    public ResponseEntity<ApiResponse<StockVoucherDtos.Response>> getVoucher(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Lấy chi tiết phiếu kho thành công",
                stockVoucherService.getVoucher(id)));
    }

    @PostMapping("/api/inventory/vouchers")
    public ResponseEntity<ApiResponse<StockVoucherDtos.Response>> createVoucher(
            @Valid @RequestBody StockVoucherDtos.CreateRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Tạo phiếu kho PENDING thành công",
                stockVoucherService.create(request, idempotencyKey, CurrentActor.name())));
    }

    @PostMapping("/api/inventory/vouchers/{id}/approve")
    public ResponseEntity<ApiResponse<StockVoucherDtos.Response>> approveVoucher(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Duyệt phiếu kho và cập nhật tồn thành công",
                stockVoucherService.approve(id, CurrentActor.name())));
    }

    @PostMapping("/api/inventory/vouchers/{id}/reject")
    public ResponseEntity<ApiResponse<StockVoucherDtos.Response>> rejectVoucher(
            @PathVariable Long id,
            @Valid @RequestBody StockVoucherDtos.DecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Từ chối phiếu kho thành công",
                stockVoucherService.reject(id, request.reason(), CurrentActor.name())));
    }

    @GetMapping("/api/inventory/suppliers")
    public ResponseEntity<ApiResponse<List<SupplierDtos.Response>>> getSuppliers() {
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách nhà cung cấp thành công",
                supplierService.getSuppliers()));
    }

    @PostMapping("/api/inventory/suppliers")
    public ResponseEntity<ApiResponse<SupplierDtos.Response>> createSupplier(
            @Valid @RequestBody SupplierDtos.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Tạo nhà cung cấp thành công",
                supplierService.create(request)));
    }

    @PutMapping("/api/inventory/suppliers/{id}")
    public ResponseEntity<ApiResponse<SupplierDtos.Response>> updateSupplier(
            @PathVariable Long id, @Valid @RequestBody SupplierDtos.Request request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật nhà cung cấp thành công",
                supplierService.update(id, request)));
    }

    @PatchMapping("/api/inventory/suppliers/{id}/active")
    public ResponseEntity<ApiResponse<SupplierDtos.Response>> setSupplierActive(
            @PathVariable Long id, @Valid @RequestBody SupplierDtos.ActiveRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật trạng thái nhà cung cấp thành công",
                supplierService.setActive(id, request.active())));
    }

    @GetMapping("/api/inventory/defects")
    public ResponseEntity<ApiResponse<List<DefectiveInventoryDTO>>> getDefectiveInventory() {
        return ResponseEntity.ok(ApiResponse.success("Lấy tồn kho cách ly thành công",
                stockVoucherService.getDefectiveInventory()));
    }
}
