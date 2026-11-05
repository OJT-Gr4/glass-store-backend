package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.order.dto.CartDtos;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Cart;
import com.glassstore.order.service.CartService;
import com.glassstore.order.service.OrderCheckoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CartController {

    private final CartService cartService;
    private final OrderCheckoutService checkoutService;
    private final com.glassstore.order.service.OmsWorkflowService omsWorkflow;

    @GetMapping
    public ResponseEntity<ApiResponse<CartDtos.CartView>> getCart(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionToken) {
        customerId = com.glassstore.common.security.CurrentActor.id();
        return ResponseEntity.ok(ApiResponse.success("OK", cartService.getOrCreate(customerId, sessionToken)));
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartDtos.CartView>> addItem(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionToken,
            @Valid @RequestBody CartDtos.AddItemRequest request) {
        customerId = com.glassstore.common.security.CurrentActor.id();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Đã thêm vào giỏ hàng",
                        cartService.addItem(customerId, sessionToken, request)));
    }

    @PatchMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<CartDtos.CartView>> updateItem(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionToken,
            @PathVariable("itemId") Long itemId,
            @Valid @RequestBody CartDtos.UpdateQtyRequest request) {
        customerId = com.glassstore.common.security.CurrentActor.id();
        return ResponseEntity.ok(ApiResponse.success("Đã cập nhật số lượng",
                cartService.updateQuantity(customerId, sessionToken, itemId, request.getQuantity())));
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<CartDtos.CartView>> removeItem(
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionToken,
            @PathVariable("itemId") Long itemId) {
        customerId = com.glassstore.common.security.CurrentActor.id();
        return ResponseEntity.ok(ApiResponse.success("Đã xóa sản phẩm khỏi giỏ",
                cartService.removeItem(customerId, sessionToken, itemId)));
    }

    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<com.glassstore.order.entity.Order>> checkout(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionToken,
            @Valid @RequestBody OrderDtos.CheckoutRequest request) {
        customerId = com.glassstore.common.security.CurrentActor.id();

        request.setChannel("ONLINE");
        request.setCustomerId(customerId);
        Cart cart = cartService.requireCart(customerId, sessionToken);
        request.setItems(cartService.toCheckoutLines(cart));
        omsWorkflow.requireCustomerPrescriptions(request);
        if (customerId != null) {
            request.setCustomerId(customerId);
        }
        OrderCheckoutService.CheckoutOutcome outcome = checkoutService.checkout(request, idempotencyKey);
        com.glassstore.common.security.CurrentActor.requireOwner(outcome.order().getCustomerId());
        if (!outcome.replayed()) {
            cartService.clear(cart);
        }
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        String message = outcome.replayed()
                ? "Đơn hàng đã được tạo trước đó (yêu cầu được gửi lại)"
                : "Tạo đơn hàng thành công";
        return ResponseEntity.status(status).body(ApiResponse.success(message, outcome.order()));
    }
}
