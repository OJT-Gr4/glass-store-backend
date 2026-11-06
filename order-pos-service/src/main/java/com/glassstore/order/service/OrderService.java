package com.glassstore.order.service;
import com.glassstore.order.domain.*;

import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderItem;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.service.StockDeductionService;
import com.glassstore.order.service.SalesBranch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.RoundingMode;
import com.glassstore.order.dto.OrderDtos;
import java.util.ArrayList;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final StockDeductionService stockDeductionService;
    private final com.glassstore.order.client.CatalogInventoryClient catalogInventoryClient;
    private final PromotionService promotions;
    private final PosDataService data;
    private final OrderStatusService orderStatusService;
    private final LabDispatchService labDispatchService;
    private final WarrantyActivationService warrantyActivationService;
    private final CheckoutIdempotencyService idempotency;

    @Transactional
    public ResponseEntity<Order> createCounterSale(OrderDtos.CheckoutRequest request, String key, String token) {
        Order order = Order.builder().channel(SalesChannel.POS)
                .customerId(request.getCustomerId()).customerName(request.getCustomerName())
                .customerPhone(request.getCustomerPhone()).paymentMethod(PaymentMethod.parse(request.getPaymentMethod()))
                .orderType(OrderType.parse(request.getOrderType())).prescriptionId(request.getPrescriptionId())
                .branchName(request.getBranchName()).branchCode(request.getBranchCode())
                .staffName(request.getStaffName()).posSessionId(request.getPosSessionId()).notes(request.getNotes())
                .voucherCode(request.getVoucherCode()).pointsToUse(request.getPointsToUse())
                .cashReceived(request.getCashReceived()).manualPaymentConfirmed(request.isManualPaymentConfirmed())
                .paymentReference(request.getPaymentReference()).checkoutRequestHash(idempotency.fingerprint(request))
                .items(new ArrayList<>()).build();
        for (OrderDtos.CheckoutLine line : request.getItems()) {
            order.getItems().add(OrderItem.builder().skuId(line.getSku()).productName(line.getProductName() == null ? line.getSku() : line.getProductName())
                    .quantity(line.getQuantity()).prescriptionId(line.getPrescriptionId()).expectedUnitPrice(line.getExpectedUnitPrice()).build());
        }
        return create(order, key, token);
    }

    @Transactional
    public ResponseEntity<Order> create(Order order, String requestIdempotencyKey, String token) {
        if (order.getChannel() != SalesChannel.POS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Đơn online phải dùng luồng checkout chờ thanh toán.");
        var actor = data.actor(token,false);
        String actorName = actor.path("username").asText(actor.path("id").asText());
        var checkout = idempotency.begin(requestIdempotencyKey,
                order.getCheckoutRequestHash()!=null?order.getCheckoutRequestHash():idempotency.fingerprint(order),
                "STAFF:" + actor.path("id").asText() + ":" + actorName);
        if (checkout.getOrderId() != null) {
            Order previous = orderRepository.findById(checkout.getOrderId()).orElseThrow();
            previous.setInventorySyncStatus(stockDeductionService.statusForOrder(previous.getId()));
            return ResponseEntity.ok(previous);
        }
        if (paymentRepository.findByIdempotencyKey(requestIdempotencyKey).isPresent()
                || orderRepository.findByIdempotencyKey(requestIdempotencyKey).isPresent())
            throw com.glassstore.common.exception.ApiException.of(com.glassstore.common.exception.ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key thuộc đơn cũ chưa có dấu nội dung. Tra cứu đơn cũ, không thanh toán lại.");
        if (order.getPaymentMethod()!=PaymentMethod.CASH && !order.isManualPaymentConfirmed())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Thu ngân phải xác nhận đã nhận tiền chuyển khoản/thẻ tại quầy.");
        order.setStaffName(actorName);
        if (order.getChannel() == SalesChannel.POS) promotions.calculate(order, token, true);
        if (order.getId() != null || order.getItems() == null || order.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đơn hàng phải có sản phẩm và không được có ID sẵn.");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : order.getItems()) {
            if (item == null || item.getSkuId() == null || item.getSkuId().isBlank()
                    || item.getProductName() == null || item.getProductName().isBlank()
                    || item.getQuantity() == null || item.getQuantity() <= 0
                    || item.getUnitPrice() == null || item.getUnitPrice().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU, tên, số lượng và đơn giá sản phẩm không hợp lệ.");
            }
            item.setId(null);
            item.setSubtotal(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            total = total.add(item.getSubtotal());
        }
        BigDecimal discount = order.getDiscountAmount() == null ? BigDecimal.ZERO : order.getDiscountAmount();
        if (discount.signum() < 0 || discount.compareTo(total) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Giảm giá không hợp lệ.");
        }
        if (order.getChannel() == SalesChannel.POS) {
            SalesBranch branch = SalesBranch.resolve(order.getBranchCode(), order.getBranchName());
            order.setBranchCode(branch.code());
            order.setBranchName(branch.name());
            try {
                catalogInventoryClient.checkAvailability(new com.glassstore.order.dto.StockCheckRequest(branch.code(), order.getItems().stream()
                        .map(item -> new com.glassstore.order.dto.CatalogStockDeductionRequest.Item(item.getSkuId(), item.getQuantity())).toList()));
            } catch (feign.FeignException error) {
                throw new ResponseStatusException(error.status() >= 400 && error.status() < 500 ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE,
                        "Chưa thanh toán: chi nhánh không đủ tồn hoặc dịch vụ kho chưa sẵn sàng.");
            }
            order.setChannel(SalesChannel.POS);
            if (!List.of(PaymentMethod.CASH, PaymentMethod.VIETQR, PaymentMethod.CARD).contains(order.getPaymentMethod())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phương thức thanh toán POS không hợp lệ.");
            }
            order.setTotalAmount(total);
            order.setDiscountAmount(discount);
            order.setTaxAmount(total.subtract(discount).multiply(new BigDecimal("0.08")).setScale(0, RoundingMode.HALF_UP));
            order.setFinalAmount(total.subtract(discount).add(order.getTaxAmount()));
            order.setStatus(OrderStatus.PAID);
        }
        if (order.getCustomerName() == null || order.getCustomerName().isBlank()) order.setCustomerName("Khách vãng lai");
        if (order.getCustomerPhone() == null) order.setCustomerPhone("");
        if (order.getOrderCode() == null || order.getOrderCode().isEmpty()) {
            order.setOrderCode("OPT-" + UUID.randomUUID().toString().toUpperCase());
        }
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());

        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                item.setOrder(order);
                if (item.getSubtotal() == null && item.getUnitPrice() != null) {
                    item.setSubtotal(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity() != null ? item.getQuantity() : 1)));
                }
            }
        }

        if (order.getFinalAmount() == null && order.getTotalAmount() != null) {
            order.setFinalAmount(order.getTotalAmount().subtract(discount));
        }

        order.setOrderCode("OPT-" + UUID.randomUUID().toString().toUpperCase());
        order.setCancelReason(null); order.setCancelledBy(null); order.setCancelledAt(null);
        order.setRefundStatus(null); order.setRefundConfirmedBy(null); order.setRefundConfirmedAt(null);
        if ((order.getChannel() == SalesChannel.POS) && (order.getPaymentMethod() == PaymentMethod.CASH)
                && (order.getCashReceived() == null || order.getCashReceived().compareTo(order.getFinalAmount()) < 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tiền khách đưa chưa đủ theo tổng tiền backend.");
        order.setIdempotencyKey(requestIdempotencyKey);
        if (order.getChannel() == SalesChannel.POS) order.setStatus(OrderStatus.PENDING_PAYMENT);
        Order saved = orderRepository.save(order);

        if ((saved.getChannel() == SalesChannel.POS)) {
            saved = orderStatusService.transition(saved, OrderStatus.PAID, saved.getStaffName(), "Thanh toán tại quầy");
            promotions.consume(saved);
        }

        // Record payment
        Payment payment = Payment.builder()
                .orderId(saved.getId())
                .paymentMethod(saved.getPaymentMethod() != null ? saved.getPaymentMethod() : PaymentMethod.VIETQR)
                .amount(saved.getFinalAmount() != null ? saved.getFinalAmount() : BigDecimal.ZERO)
                .transactionCode("TXN-" + UUID.randomUUID().toString().toUpperCase())
                .idempotencyKey(requestIdempotencyKey != null
                        ? requestIdempotencyKey
                        : UUID.randomUUID().toString())
                .status(PaymentStatus.SUCCESS)
                .paidAt(LocalDateTime.now())
                .confirmationMode(saved.getPaymentMethod()==PaymentMethod.CASH?"CASH_RECEIVED":saved.getPaymentMethod()==PaymentMethod.VIETQR?"MANUAL_TRANSFER":"CARD_TERMINAL")
                .confirmedBy(actorName).confirmedAt(LocalDateTime.now()).paymentReference(order.getPaymentReference())
                .build();
        paymentRepository.save(payment);
        checkout.setOrderId(saved.getId());
        if (saved.getChannel() == SalesChannel.POS && payment.getStatus() == PaymentStatus.SUCCESS) {
            stockDeductionService.enqueue(saved, payment);
            warrantyActivationService.enqueueForPaidOrder(saved);
            labDispatchService.enqueue(saved);
        }

        log.info("Tạo đơn hàng POS thành công: Mã {}", saved.getOrderCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }
}
