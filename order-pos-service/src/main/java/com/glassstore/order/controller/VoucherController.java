package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.entity.Voucher;
import com.glassstore.order.repository.VoucherRepository;
import com.glassstore.order.service.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/vouchers")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class VoucherController {

    private final VoucherService voucherService;
    private final VoucherRepository voucherRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<Voucher>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success("OK", voucherService.getAllVouchers()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Voucher>> create(@RequestBody Voucher voucher) {
        return ResponseEntity.ok(ApiResponse.success("Tạo mã giảm giá thành công", voucherService.createVoucher(voucher)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Voucher>> update(
            @PathVariable("id") Long id,
            @RequestBody Voucher voucher) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật mã giảm giá thành công", voucherService.updateVoucher(id, voucher)));
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<ApiResponse<Voucher>> toggle(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success("Thay đổi trạng thái thành công", voucherService.toggleActive(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable("id") Long id) {
        voucherService.deleteVoucher(id);
        return ResponseEntity.ok(ApiResponse.success("Xóa mã giảm giá thành công", null));
    }

    @GetMapping("/{code}")
    public ResponseEntity<ApiResponse<Voucher>> get(@PathVariable("code") String code) {
        Voucher voucher = voucherRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> com.glassstore.common.exception.ApiException.of(
                        com.glassstore.common.exception.ErrorCode.VOUCHER_NOT_FOUND,
                        "Mã giảm giá không tồn tại"));
        return ResponseEntity.ok(ApiResponse.success("OK", voucher));
    }

    @GetMapping("/{code}/quote")
    public ResponseEntity<ApiResponse<Map<String, Object>>> quote(
            @PathVariable("code") String code,
            @RequestParam("total") BigDecimal total,
            @RequestParam(name = "channel", required = false) String channel) {
        SalesChannel salesChannel = SalesChannel.parse(channel);
        BigDecimal discount = voucherService.calculateDiscount(code, total, salesChannel);
        return ResponseEntity.ok(ApiResponse.success("OK", Map.of(
                "code", code,
                "discountAmount", discount,
                "finalAmount", total.subtract(discount).max(BigDecimal.ZERO)
        )));
    }
}
