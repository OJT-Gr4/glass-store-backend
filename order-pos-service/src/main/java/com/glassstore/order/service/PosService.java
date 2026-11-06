package com.glassstore.order.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.client.CatalogContracts;
import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.PosSession;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PosSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class PosService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PosSessionRepository sessionRepository;
    private final OrderRepository orderRepository;
    private final BasketPricingService pricingService;
    private final OrderService counterSales;

    @Transactional
    public PosSession openSession(String staffName, String branchName) {
        if (staffName == null || staffName.isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Phải ghi nhận nhân viên phụ trách phiên POS");
        }
        PosSession session = PosSession.builder()
                .sessionCode(nextSessionCode())
                .staffName(staffName.trim())
                .branchName(branchName)
                .status("OPEN")
                .build();
        return sessionRepository.save(session);
    }

    @Transactional
    public PosSession closeSession(Long sessionId) {
        PosSession session = requireOpen(sessionId, false);
        session.setStatus("CLOSED");
        session.setClosedAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    public PosSession getSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.of(ErrorCode.POS_SESSION_NOT_FOUND,
                        "Không tìm thấy phiên POS ID: " + sessionId));
    }

    public CatalogContracts.VariantInfo lookupSku(String sku) {
        CatalogContracts.VariantInfo info = pricingService.lookup(sku);
        pricingService.requireSellable(info, sku);
        return info;
    }

    public CatalogContracts.VariantInfo lookupBarcode(String barcode) {
        CatalogContracts.VariantInfo info = pricingService.lookupBarcode(barcode);
        pricingService.requireSellable(info, info.getSku());
        return info;
    }

    public List<CatalogContracts.VariantInfo> search(String keyword) {
        return pricingService.search(keyword);
    }

    public OrderDtos.PricedBasket preview(OrderDtos.CheckoutRequest request) {
        return pricingService.price(request.getItems(), request.getVoucherCode(), SalesChannel.POS);
    }

    @Transactional
    public OrderCheckoutService.CheckoutOutcome checkout(OrderDtos.CheckoutRequest request, String idempotencyKey,String token) {
        PosSession session = requireOpen(request.getPosSessionId(), true);
        request.setChannel(SalesChannel.POS.name());
        request.setStaffName(session.getStaffName());
        request.setBranchName(session.getBranchName());
        if (request.getPaymentMethod() == null || request.getPaymentMethod().isBlank()) {
            request.setPaymentMethod(PaymentMethod.CASH.name());
        }

        var response=counterSales.createCounterSale(request,idempotencyKey,token);
        return new OrderCheckoutService.CheckoutOutcome(response.getBody(),response.getStatusCode().value()==200);
    }

    public List<Order> salesHistory() {
        return orderRepository.findByChannelOrderByCreatedAtDesc(SalesChannel.POS);
    }

    public Map<String, Object> invoice(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND,
                        "Không tìm thấy đơn hàng ID: " + orderId));
        if (order.getChannel() != SalesChannel.POS) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Đơn hàng này không phải đơn POS");
        }
        Map<String, Object> invoice = new java.util.LinkedHashMap<>();
        invoice.put("invoiceNumber", order.getInvoiceNumber() == null ? "INV-" + order.getOrderCode() : order.getInvoiceNumber());
        invoice.put("orderCode", order.getOrderCode());
        invoice.put("staffName", order.getStaffName() == null ? "" : order.getStaffName());
        invoice.put("branchName", order.getBranchName() == null ? "" : order.getBranchName());
        invoice.put("customerName", order.getCustomerName());
        invoice.put("customerPhone", order.getCustomerPhone());
        invoice.put("items", order.getItems());
        invoice.put("totalAmount", order.getTotalAmount());
        invoice.put("discountAmount", order.getDiscountAmount());
        invoice.put("finalAmount", order.getFinalAmount());
        invoice.put("paymentMethod", order.getPaymentMethod() == null ? "" : order.getPaymentMethod().name());
        invoice.put("status", order.currentStatus().name());
        invoice.put("createdAt", order.getCreatedAt());
        return invoice;
    }

    private PosSession requireOpen(Long sessionId, boolean mustBeOpen) {
        if (sessionId == null) {
            throw ApiException.of(ErrorCode.POS_SESSION_NOT_FOUND, "Thiếu mã phiên bán hàng POS");
        }
        PosSession session = getSession(sessionId);
        if (mustBeOpen && !"OPEN".equalsIgnoreCase(session.getStatus())) {
            throw ApiException.of(ErrorCode.POS_SESSION_CLOSED, "Phiên bán hàng đã đóng");
        }
        return session;
    }

    private String nextSessionCode() {
        String date = java.time.LocalDate.now().format(DATE);
        return "POS-" + date + "-" + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
