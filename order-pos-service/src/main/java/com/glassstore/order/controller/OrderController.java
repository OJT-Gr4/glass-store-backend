package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderStatusHistory;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.OrderStatusHistoryRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.service.OrderCheckoutService;
import com.glassstore.order.service.OrderStatusService;
import jakarta.validation.Valid;
import com.glassstore.order.service.OrderService;
import com.glassstore.order.service.OrderCancellationService;
import com.glassstore.order.service.StockDeductionService;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Order API for both the storefront and the internal portal.
 *
 * <p>{@code GET /api/orders} intentionally returns a bare array (not an envelope) because the
 * existing portal does {@code Array.isArray(...)} on the response. Every other endpoint uses
 * the standard {@link ApiResponse} shape.</p>
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class OrderController {
    private final com.glassstore.order.service.OmsWorkflowService omsWorkflow;

    private final OrderService posOrderService;
    private final OrderCancellationService posCancellations;
    private final StockDeductionService stockDeductionService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderCheckoutService checkoutService;
    private final OrderStatusService orderStatusService;

    /** Bare array to keep the existing portal contract working. */
    @GetMapping
    public ResponseEntity<List<Order>> getAllOrders(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "channel", required = false) String channel) {
        if (com.glassstore.common.security.CurrentActor.customer()) return ResponseEntity.ok(orderRepository.findByCustomerIdOrderByCreatedAtDesc(com.glassstore.common.security.CurrentActor.id()));
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            OrderStatus st = null;
            try { st = OrderStatus.parse(status); } catch (Exception ignored) {}
            SalesChannel ch = null;
            if (channel != null && !channel.isBlank() && !"ALL".equalsIgnoreCase(channel)) {
                try { ch = SalesChannel.parse(channel); } catch (Exception ignored) {}
            }
            if (st != null && ch != null) {
                return ResponseEntity.ok(orderRepository.findByStatusAndChannelOrderByCreatedAtDesc(st, ch));
            } else if (st != null) {
                return ResponseEntity.ok(orderRepository.findByStatusOrderByCreatedAtDesc(st));
            }
        } else if (channel != null && !channel.isBlank() && !"ALL".equalsIgnoreCase(channel)) {
            SalesChannel ch = null;
            try { ch = SalesChannel.parse(channel); } catch (Exception ignored) {}
            if (ch != null) {
                return ResponseEntity.ok(orderRepository.findByChannelOrderByCreatedAtDesc(ch));
            }
        }
        return ResponseEntity.ok(orderRepository.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<List<Order>>> getByCustomer(@PathVariable("customerId") Long customerId) {
        com.glassstore.common.security.CurrentActor.requireOwner(customerId);
        return ResponseEntity.ok(ApiResponse.success("OK",
                orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Order>> getOrderById(
            @PathVariable("id") Long id,
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId) {
        Order order = requireOrder(id);
        com.glassstore.common.security.CurrentActor.requireOwner(order.getCustomerId());
        stockDeductionService.inventoryStatus(order);
        return ResponseEntity.ok(ApiResponse.success("OK", order));
    }

    @GetMapping("/code/{orderCode}")
    public ResponseEntity<ApiResponse<Order>> getOrderByCode(@PathVariable("orderCode") String orderCode) {
        Order order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> com.glassstore.common.exception.ApiException.of(
                        com.glassstore.common.exception.ErrorCode.ORDER_NOT_FOUND,
                        "Không tìm thấy đơn hàng: " + orderCode));
        com.glassstore.common.security.CurrentActor.requireOwner(order.getCustomerId());
        return ResponseEntity.ok(ApiResponse.success("OK", order));
    }

    /** Payment history for an order (FR-PAY-08). */
    @GetMapping("/{id}/payments")
    public ResponseEntity<ApiResponse<List<Payment>>> getPayments(@PathVariable("id") Long id) {
        com.glassstore.common.security.CurrentActor.requireOwner(requireOrder(id).getCustomerId());
        return ResponseEntity.ok(ApiResponse.success("OK",
                paymentRepository.findByOrderIdOrderByCreatedAtDesc(id)));
    }

    /** Audit trail of status changes (SRS §4.6). */
    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<OrderStatusHistory>>> getHistory(@PathVariable("id") Long id) {
        requireOrder(id);
        return ResponseEntity.ok(ApiResponse.success("OK",
                historyRepository.findByOrderIdOrderByChangedAtAsc(id)));
    }

    /**
     * Checkout. Requires {@code Idempotency-Key}; a replay returns 200 with the original
     * order, a first submission returns 201.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Order>> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderDtos.CheckoutRequest request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        if (com.glassstore.common.security.CurrentActor.customer()) {
            if (!"ONLINE".equalsIgnoreCase(request.getChannel())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            request.setCustomerId(com.glassstore.common.security.CurrentActor.id());
        }
        omsWorkflow.requireCustomerPrescriptions(request);

        prepareCounterSale(request);
        SalesChannel channel = SalesChannel.parse(request.getChannel());
        if (channel == SalesChannel.POS) {
            ResponseEntity<Order> result = posOrderService.createCounterSale(request, idempotencyKey, token);
            return ResponseEntity.status(result.getStatusCode()).body(ApiResponse.success("Thanh toán tại quầy", result.getBody()));
        }
        OrderCheckoutService.CheckoutOutcome outcome = checkoutService.checkout(request, idempotencyKey);
        Order order = outcome.order();
        com.glassstore.common.security.CurrentActor.requireOwner(order.getCustomerId());
        if (order.getItems() != null) {
            order.getItems().size();
        }

        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        String message = outcome.replayed()
                ? "Đơn hàng đã được tạo trước đó (yêu cầu được gửi lại)"
                : (channel == SalesChannel.POS
                ? "Thanh toán tại quầy thành công"
                : "Tạo đơn hàng thành công");
        return ResponseEntity.status(status).body(ApiResponse.success(message, order));
    }

    /** Staff confirms cash received at the counter (FR-PAY-04). */
    @PostMapping("/{id}/settle-cash")
    public ResponseEntity<ApiResponse<Order>> settleCash(
            @PathVariable("id") Long id,
            @RequestParam(name = "actor", required = false) String actor) {
        return ResponseEntity.ok(ApiResponse.success("Xác nhận thanh toán tiền mặt thành công",
                checkoutService.settleCash(id, com.glassstore.common.security.CurrentActor.name())));
    }

    /** Validated status change (SRS §4.6). */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Order>> updateOrderStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody OrderDtos.StatusUpdateRequest body) {

        return ResponseEntity.ok(ApiResponse.success("OK", omsWorkflow.transition(id, body, com.glassstore.common.security.CurrentActor.name())));
    }


    @PatchMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<Order>> cancelCounterSale(@PathVariable("id") Long id,
            @RequestBody OrderCancellationService.Request request,
            @RequestHeader(value = "Authorization", required = false) String token) {
        return ResponseEntity.ok(ApiResponse.success("Hủy đơn tại quầy", posCancellations.cancel(id, request, token)));
    }

    @PostMapping("/{id}/refund-confirm")
    public ResponseEntity<ApiResponse<Order>> confirmCounterRefund(@PathVariable("id") Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        return ResponseEntity.ok(ApiResponse.success("Xác nhận hoàn tiền", posCancellations.confirmRefund(id, token)));
    }

    @PostMapping("/{id}/inventory/retry")
    public ResponseEntity<ApiResponse<Order>> retryInventory(@PathVariable("id") Long id,
            @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success("Đồng bộ kho",
                stockDeductionService.retry(id, body == null ? null : body.get("branchCode"))));
    }

    /** Dispatch lệnh gia công mài kính sang Lab cho đơn hàng. */
    @PostMapping("/{id}/dispatch-lab")
    public ResponseEntity<ApiResponse<Order>> dispatchLabJob(@PathVariable("id") Long id) {
        Order order = requireOrder(id);
        Order updated = checkoutService.dispatchLabJob(order);
        if (updated.currentStatus() == OrderStatus.LAB_DISPATCH_FAILED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.<Order>builder()
                    .code(503).message("Chưa gửi được lệnh sang Lab. Vui lòng thử lại.").data(updated).build());
        }
        return ResponseEntity.ok(ApiResponse.success("Gửi lệnh mài kính sang Lab thành công", updated));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> businessError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",
                error.getReason() == null ? "Yêu cầu không hợp lệ." : error.getReason()));
    }

    private Order requireOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> com.glassstore.common.exception.ApiException.of(
                        com.glassstore.common.exception.ErrorCode.ORDER_NOT_FOUND,
                        "Không tìm thấy đơn hàng ID: " + id));
    }

    /**
     * POS payload from the portal historically used {@code skuId} and omitted walk-in phone /
     * order type. Normalize here so {@code POST /api/orders} remains the cashier endpoint.
     */
    private void prepareCounterSale(OrderDtos.CheckoutRequest request) {
        if (request.getChannel() == null || request.getChannel().isBlank()) {
            return;
        }
        SalesChannel channel;
        try {
            channel = SalesChannel.parse(request.getChannel());
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (channel != SalesChannel.POS) {
            return;
        }
        request.setAcceptPriceChange(true);
        java.util.Set<Long> linePrescriptions = new java.util.HashSet<>();
        if (request.getPrescriptionId() != null) linePrescriptions.add(request.getPrescriptionId());
        if (request.getItems() != null) request.getItems().stream().map(OrderDtos.CheckoutLine::getPrescriptionId)
                .filter(java.util.Objects::nonNull).forEach(linePrescriptions::add);
        if (linePrescriptions.size() > 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Mỗi đơn POS chỉ liên kết một toa kính. Vui lòng tách đơn cho các toa khác nhau.");
        if (!linePrescriptions.isEmpty()) request.setPrescriptionId(linePrescriptions.iterator().next());
        if (request.getCustomerName() == null || request.getCustomerName().isBlank()) {
            request.setCustomerName("Khách vãng lai");
        }
        if (request.getCustomerPhone() == null || request.getCustomerPhone().isBlank()) {
            request.setCustomerPhone("0000000000");
        }
        if (request.getOrderType() == null || request.getOrderType().isBlank()) {
            request.setOrderType(inferPosOrderType(request));
        }
    }

    private String inferPosOrderType(OrderDtos.CheckoutRequest request) {
        boolean lens = false;
        boolean frame = false;
        boolean accessory = false;
        if (request.getItems() != null) {
            for (OrderDtos.CheckoutLine line : request.getItems()) {
                String sku = line.getSku() == null ? "" : line.getSku().toUpperCase();
                if (sku.startsWith("LNS-") || sku.startsWith("LZ-")) {
                    lens = true;
                } else if (sku.startsWith("ACC-")) {
                    accessory = true;
                } else {
                    frame = true;
                }
            }
        }
        if (request.getPrescriptionId() != null && (lens || frame)) {
            return lens && !frame ? "LENS_ONLY" : "COMPLETE_GLASSES";
        }
        if (accessory && !frame && !lens) {
            return "ACCESSORY";
        }
        if (lens && !frame) {
            return "ACCESSORY";
        }
        return "FRAME_ONLY";
    }

    private void assertOwner(Order order, Long customerId) {
        if (customerId == null) {
            return;
        }
        if (order.getCustomerId() == null || !order.getCustomerId().equals(customerId)) {
            throw com.glassstore.common.exception.ApiException.of(
                    com.glassstore.common.exception.ErrorCode.ORDER_FORBIDDEN,
                    "Bạn không có quyền truy cập đơn hàng này");
        }
    }
}
