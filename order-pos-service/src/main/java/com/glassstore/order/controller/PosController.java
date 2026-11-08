package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.order.client.CatalogContracts;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.PosSession;
import com.glassstore.order.service.OrderCheckoutService;
import com.glassstore.order.service.PosService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pos")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PosController {

    private final PosService posService;

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<PosSession>> openSession(@RequestBody OpenSessionRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success("Đã mở phiên bán hàng",
                        posService.openSession(com.glassstore.common.security.CurrentActor.name(),
                                body == null ? null : body.getBranchName())));
    }

    @PostMapping("/sessions/{id}/close")
    public ResponseEntity<ApiResponse<PosSession>> closeSession(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success("Đã đóng phiên bán hàng", posService.closeSession(id)));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<ApiResponse<PosSession>> getSession(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success("OK", posService.getSession(id)));
    }

    @GetMapping("/sku/{sku}")
    public ResponseEntity<ApiResponse<CatalogContracts.VariantInfo>> bySku(@PathVariable("sku") String sku) {
        return ResponseEntity.ok(ApiResponse.success("OK", posService.lookupSku(sku)));
    }

    @GetMapping("/barcode/{barcode}")
    public ResponseEntity<ApiResponse<CatalogContracts.VariantInfo>> byBarcode(
            @PathVariable("barcode") String barcode) {
        return ResponseEntity.ok(ApiResponse.success("OK", posService.lookupBarcode(barcode)));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<CatalogContracts.VariantInfo>>> search(
            @RequestParam(name = "q", required = false) String q) {
        return ResponseEntity.ok(ApiResponse.success("OK", posService.search(q)));
    }

    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<OrderDtos.PricedBasket>> preview(
            @Valid @RequestBody OrderDtos.CheckoutRequest request) {
        return ResponseEntity.ok(ApiResponse.success("OK", posService.preview(request)));
    }

    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<Order>> checkout(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderDtos.CheckoutRequest request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        request.setStaffName(com.glassstore.common.security.CurrentActor.name());
        OrderCheckoutService.CheckoutOutcome outcome = posService.checkout(request, idempotencyKey,token);
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(
                ApiResponse.success(outcome.replayed()
                        ? "Giao dịch đã được ghi nhận trước đó"
                        : "Thanh toán tại quầy thành công", outcome.order()));
    }

    @GetMapping("/sales")
    public ResponseEntity<ApiResponse<List<Order>>> salesHistory() {
        return ResponseEntity.ok(ApiResponse.success("OK", posService.salesHistory()));
    }

    @GetMapping("/sales/{id}/invoice")
    public ResponseEntity<ApiResponse<Map<String, Object>>> invoice(@PathVariable("id") Long id) {
        return ResponseEntity.ok(ApiResponse.success("OK", posService.invoice(id)));
    }

    @Data
    public static class OpenSessionRequest {
        private String staffName;
        private String branchName;
    }
}
