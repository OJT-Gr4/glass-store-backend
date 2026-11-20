package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.common.security.CurrentActor;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.VoucherRepository;
import com.glassstore.order.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;

@RestController @RequestMapping("/api/vouchers") @RequiredArgsConstructor
public class VoucherController {
    private final VoucherService voucherService;
    private final VoucherRepository voucherRepository;
    private final VoucherEntitlementService entitlements;
    private void staff() {
        if (!CurrentActor.hasRole("ADMIN") && !CurrentActor.hasRole("SALES_STAFF")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    private Long owner(Long customerId) {
        if (CurrentActor.customer()) return CurrentActor.id();
        staff(); return customerId;
    }
    @GetMapping
    public ResponseEntity<ApiResponse<List<Voucher>>> getAll(@RequestParam(name="customerId", required=false) Long customerId) {
        Long id = owner(customerId);
        return ResponseEntity.ok(ApiResponse.success("OK", id != null ? entitlements.available(id) : voucherService.getAllVouchers()));
    }
    @PostMapping
    public ResponseEntity<ApiResponse<Voucher>> create(@RequestBody Voucher voucher) {
        staff(); return ResponseEntity.ok(ApiResponse.success("Đã tạo voucher", voucherService.createVoucher(voucher)));
    }
    @PutMapping("/{id}") @Transactional
    public ResponseEntity<ApiResponse<Voucher>> update(@PathVariable("id") Long id, @RequestBody Voucher voucher) {
        staff(); return ResponseEntity.ok(ApiResponse.success("Đã cập nhật voucher", voucherService.updateVoucher(id, voucher)));
    }
    @PatchMapping("/{id}/toggle") @Transactional
    public ResponseEntity<ApiResponse<Voucher>> toggle(@PathVariable("id") Long id) {
        staff(); return ResponseEntity.ok(ApiResponse.success("Đã cập nhật trạng thái", voucherService.toggleActive(id)));
    }
    @DeleteMapping("/{id}") @Transactional
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable("id") Long id) {
        staff(); voucherService.deleteVoucher(id); return ResponseEntity.ok(ApiResponse.success("Đã ngừng phát hành voucher", null));
    }
    @GetMapping("/{code}")
    public ResponseEntity<ApiResponse<Voucher>> get(@PathVariable("code") String code) {
        Voucher voucher;
        if (CurrentActor.customer()) voucher = entitlements.requireAvailable(code, CurrentActor.id(), SalesChannel.ONLINE);
        else { staff(); voucher = voucherRepository.findByCodeIgnoreCase(code).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mã voucher không tồn tại")); }
        return ResponseEntity.ok(ApiResponse.success("OK", voucher));
    }
    @GetMapping("/{code}/quote")
    public ResponseEntity<ApiResponse<Map<String,Object>>> quote(@PathVariable("code") String code,
            @RequestParam("total") BigDecimal total, @RequestParam(name="channel", required=false) String channel,
            @RequestParam(name="customerId", required=false) Long customerId) {
        if (total == null || total.signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tổng tiền không hợp lệ");
        Long id = owner(customerId);
        SalesChannel salesChannel = CurrentActor.customer() ? SalesChannel.ONLINE : SalesChannel.parse(channel);
        entitlements.requireAvailable(code, id, salesChannel);
        BigDecimal discount = voucherService.calculateDiscount(code, total, salesChannel);
        return ResponseEntity.ok(ApiResponse.success("OK", Map.of("code",code.trim().toUpperCase(Locale.ROOT),"discountAmount",discount,"finalAmount",total.subtract(discount).max(BigDecimal.ZERO))));
    }
    public record IssueRequest(List<Long> customerIds) {}
    @PostMapping("/{id}/grants")
    public ResponseEntity<ApiResponse<List<VoucherGrant>>> issue(@PathVariable("id") Long id, @RequestBody IssueRequest request,
            @RequestHeader(value="Authorization", required=false) String token) {
        staff(); return ResponseEntity.ok(ApiResponse.success("Đã cấp voucher", entitlements.issue(id, request.customerIds(), token)));
    }
    @GetMapping("/{id}/grants")
    public ResponseEntity<ApiResponse<List<VoucherGrant>>> issued(@PathVariable("id") Long id) {
        staff(); return ResponseEntity.ok(ApiResponse.success("OK", entitlements.issued(id)));
    }
    @DeleteMapping("/{id}/grants/{customerId}")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable("id") Long id, @PathVariable("customerId") Long customerId) {
        staff(); entitlements.revoke(id, customerId); return ResponseEntity.ok(ApiResponse.success("Đã thu hồi voucher", null));
    }
}
