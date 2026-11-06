package com.glassstore.order.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.client.LabClient;
import com.glassstore.order.client.LabServiceClient;
import com.glassstore.order.domain.*;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.dto.CreateLabJobRequest;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderItem;
import com.glassstore.order.entity.OrderPrescription;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Checkout and settlement.
 *
 * <p>The client may only choose what to buy, how to pay, and contact details.
 * Prices and totals are recomputed server-side (BR-05, BR-16).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCheckoutService {

    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final BasketPricingService pricingService;
    private final OrderStatusService orderStatusService;
    private final OrderInventoryService inventoryService;
    private final LabClient labClient;
    private final WarrantyActivationService warrantyActivationService;
    private final LabServiceClient labServiceClient;
    private final CheckoutIdempotencyService idempotency;
    private final LabDispatchService labDispatchService;

    @Transactional
    public CheckoutOutcome checkout(OrderDtos.CheckoutRequest request, String idempotencyKey) {
        String key = requireIdempotencyKey(idempotencyKey);
        var claim = idempotency.begin(key,idempotency.fingerprint(request),"CUSTOMER:" + request.getCustomerId());
        if (claim.getOrderId() != null) return new CheckoutOutcome(orderRepository.findById(claim.getOrderId()).orElseThrow(),true);

        Optional<Order> existing = orderRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            throw ApiException.of(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key thuộc đơn cũ chưa có dấu nội dung. Tra cứu đơn cũ trước khi thử lại.");
        }

        PaymentMethod method = PaymentMethod.parse(request.getPaymentMethod());
        SalesChannel channel = SalesChannel.parse(request.getChannel());
        OrderType orderType = OrderType.parse(request.getOrderType());

        OrderDtos.PricedBasket basket = pricingService.price(request.getItems(), request.getVoucherCode(), channel);

        if (basket.getPriceChanges() != null && !basket.getPriceChanges().isEmpty()
                && !request.isAcceptPriceChange()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("priceChanges", basket.getPriceChanges());
            details.put("newTotal", basket.getFinalAmount());
            throw new ApiException(ErrorCode.ORDER_PRICE_CHANGED,
                    "Giá sản phẩm đã thay đổi. Vui lòng xác nhận giá mới trước khi đặt hàng.", details);
        }

        if (request.getPrescription() != null && request.getPrescriptionId() != null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Chỉ chọn một nguồn thông số toa kính");
        }
        if (request.getPrescription() != null && !request.getPrescription().isConfirmed()) {
            throw ApiException.of(ErrorCode.PRESCRIPTION_INCOMPLETE, "Vui lòng xác nhận thông số đã cung cấp");
        }
        if (orderType.requiresPrescription() && request.getPrescriptionId() == null && request.getPrescription() == null) {
            throw ApiException.of(ErrorCode.PRESCRIPTION_INCOMPLETE,
                    "Đơn kính thuốc bắt buộc phải có toa kính đã được xác nhận");
        }

        if (request.getCustomerName() == null || request.getCustomerName().isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Thiếu tên khách hàng");
        }
        if (channel != SalesChannel.POS
                && (request.getCustomerPhone() == null || request.getCustomerPhone().isBlank())) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Thiếu số điện thoại khách hàng");
        }
        if (request.getCustomerPhone() == null || request.getCustomerPhone().isBlank()) {
            request.setCustomerPhone("0000000000");
        }

        String orderCode = nextOrderCode();
        Order order = Order.builder()
                .orderCode(orderCode)
                .idempotencyKey(key)
                .customerId(request.getCustomerId())
                .customerName(request.getCustomerName().trim())
                .customerPhone(request.getCustomerPhone().trim())
                .shippingAddress(request.getShippingAddress())
                .channel(channel)
                .orderType(orderType)
                .totalAmount(basket.getTotalAmount())
                .discountAmount(basket.getDiscountAmount())
                .finalAmount(basket.getFinalAmount())
                .status(OrderStatus.PENDING_PAYMENT)
                .paymentMethod(method)
                .voucherCode(request.getVoucherCode())
                .prescriptionId(request.getPrescriptionId())
                .staffName(request.getStaffName())
                .branchName(request.getBranchName())
                .notes(request.getNotes())
                .posSessionId(request.getPosSessionId())
                .invoiceNumber(channel == SalesChannel.POS ? "INV-" + orderCode : null)
                .build();

        if (request.getPrescription() != null) {
            var rx = request.getPrescription();
            order.setPrescription(OrderPrescription.builder()
                    .odSph(rx.getOdSph()).odCyl(rx.getOdCyl()).odAxis(rx.getOdAxis()).odAdd(rx.getOdAdd())
                    .osSph(rx.getOsSph()).osCyl(rx.getOsCyl()).osAxis(rx.getOsAxis()).osAdd(rx.getOsAdd())
                    .pd(rx.getPd()).source("CUSTOMER_PROVIDED").customerConfirmedAt(LocalDateTime.now()).build());
        }
        for (OrderDtos.OrderItemSnapshot line : basket.getLines()) {
            order.getItems().add(OrderItem.builder()
                    .order(order)
                    .skuId(line.getSku())
                    .productName(line.getProductName())
                    .quantity(line.getQuantity())
                    .unitPrice(line.getUnitPrice())
                    .subtotal(line.getSubtotal())
                    .prescriptionId(request.getPrescriptionId())
                    .build());
        }

        Order saved = orderRepository.save(order);

        Payment payment = Payment.builder()
                .orderId(saved.getId())
                .paymentMethod(method)
                .status(PaymentStatus.PENDING)
                .amount(saved.getFinalAmount())
                .provider(method.requiresGateway() ? "UNCONFIGURED" : null)
                .transactionCode("TXN-" + saved.getOrderCode())
                .build();
        paymentRepository.save(payment);

        claim.setOrderId(saved.getId());
        inventoryService.reserve(saved);

        log.info("Order {} created: {} line(s), final={}, method={}, channel={}",
                saved.getOrderCode(), saved.getItems().size(), saved.getFinalAmount(), method, channel);

        return new CheckoutOutcome(saved, false);
    }

    @Transactional
    public Order settleCash(Long orderId, String actor) {
        Order order = orderRepository.lockById(orderId)
                .orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND,
                        "Không tìm thấy đơn hàng ID: " + orderId));

        if (order.getPaymentMethod() != PaymentMethod.CASH) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Chỉ xác nhận tiền mặt cho đơn hàng có phương thức CASH");
        }
        return markPaid(order, actor, "Thu tiền mặt tại quầy", "CASH");
    }

    /**
     * Marks an order paid after a verified payment source (cash staff action or gateway).
     */
    @Transactional
    public Order markPaid(Order input, String actor, String reason, String provider) {
        return markPaid(input,actor,reason,provider,null);
    }

    @Transactional
    public Order markPaid(Order input, String actor, String reason, String provider, Long paymentId) {
        Order order = orderRepository.lockById(input.getId()).orElseThrow();
        if (paymentRepository.existsByOrderIdAndStatus(order.getId(), PaymentStatus.SUCCESS)) {
            return order;
        }

        Payment payment = paymentRepository.findByOrderIdOrderByCreatedAtDesc(order.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .filter(p -> paymentId == null || paymentId.equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> ApiException.of(ErrorCode.PAYMENT_NOT_FOUND,
                        "Không có giao dịch chờ thanh toán cho đơn " + order.getOrderCode()));

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaidAt(LocalDateTime.now());
        payment.setConfirmedBy(actor); payment.setConfirmedAt(LocalDateTime.now());
        payment.setConfirmationMode("CASH".equals(provider)?"CASH_RECEIVED":"GATEWAY_VERIFIED");
        payment.setUpdatedAt(LocalDateTime.now());
        if (provider != null) {
            payment.setProvider(provider);
        }
        if (payment.getTransactionCode() == null) {
            payment.setTransactionCode(provider + "-" + order.getOrderCode());
        }
        paymentRepository.save(payment);

        OrderStatus previous = order.currentStatus();
        Order paid = orderStatusService.transition(order, OrderStatus.PAID, actor, reason);
        inventoryService.onStatusChanged(paid, previous, OrderStatus.PAID);
        warrantyActivationService.enqueueForPaidOrder(paid);
        labDispatchService.enqueue(paid);
        return paid;
    }

    /**
     * Tự động sinh lệnh mài Lens (lab_job) qua OpenFeign khi đơn hàng có kính thuốc / toa đo mắt.
     */
    public void dispatchLabJobIfNeeded(Order order) {
        if (order == null) {
            return;
        }

        // 1. Kiểm tra nếu đơn hàng có sản phẩm là kính thuốc (hoặc có gắn toa đo mắt prescription)
        boolean hasPrescription = order.getPrescriptionId() != null;
        // A customer-provided snapshot must be reviewed before a clinical Lab job is created.
        if (!hasPrescription && order.getPrescription() != null && (order.getItems() == null
                || order.getItems().stream().noneMatch(item -> item.getPrescriptionId() != null))) return;
        boolean isPrescriptionOrder = order.getOrderType() != null && order.getOrderType().requiresPrescription();
        boolean hasPrescriptionItem = order.getItems() != null && order.getItems().stream().anyMatch(i ->
                i.getPrescriptionId() != null || isLensSku(i.getSkuId(), i.getProductName()));

        if (!hasPrescription && !isPrescriptionOrder && !hasPrescriptionItem) {
            log.debug("Đơn hàng {} không có sản phẩm kính thuốc hoặc toa kính, bỏ qua tạo lệnh mài", order.getOrderCode());
            return;
        }

        dispatchLabJob(order);
    }

    /**
     * Dispatch lệnh gia công sang eyewear-lab-service với validation đầy đủ và retry exponential backoff.
     */
    @Transactional
    public Order dispatchLabJob(Order order) {
        if (order == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Đơn hàng không hợp lệ");
        }

        // 1. Kiểm tra đơn hàng phải có Order.status == PAID
        if (order.currentStatus() != OrderStatus.PAID && order.currentStatus() != OrderStatus.LAB_DISPATCH_FAILED) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Đơn hàng phải ở trạng thái PAID để gửi lệnh gia công sang Lab (trạng thái hiện tại: " + order.currentStatus() + ")");
        }

        // 2. Kiểm tra toa kính (Prescription): Tồn tại, đúng khách hàng của đơn, đủ thông số đo (SPH, CYL, AXIS, PD)
        Long effectivePrescriptionId = order.getPrescriptionId();
        if (effectivePrescriptionId == null && order.getItems() != null) {
            effectivePrescriptionId = order.getItems().stream()
                    .map(OrderItem::getPrescriptionId)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        if (effectivePrescriptionId == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Đơn hàng kính thuốc thiếu toa kính (prescriptionId)");
        }

        Map<String, Object> rxMap = null;
        if (labServiceClient != null) {
            try {
                var rxRes = labServiceClient.getPrescriptionById(effectivePrescriptionId);
                if (rxRes != null && rxRes.getBody() != null) {
                    Map<String, Object> body = rxRes.getBody();
                    Object dataObj = body.get("data");
                    if (dataObj instanceof Map<?, ?> dataMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> casted = (Map<String, Object>) dataMap;
                        rxMap = casted;
                    } else if (body.containsKey("odSph") || body.containsKey("odSphere")) {
                        rxMap = body;
                    }
                }
            } catch (ApiException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("Lỗi khi truy vấn toa kính ID {} từ lab-service: {}", effectivePrescriptionId, ex.getMessage());
                throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Không tìm thấy toa kính với ID: " + effectivePrescriptionId);
            }
        }

        if (rxMap == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR, "Không tìm thấy thông tin toa kính với ID: " + effectivePrescriptionId);
        }

        // Đúng khách hàng của đơn hàng
        Long rxCustomerId = asLong(rxMap.get("customerId"));
        if (order.getCustomerId() == null || !order.getCustomerId().equals(rxCustomerId)) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Toa kính (customerId=" + rxCustomerId + ") không đúng khách hàng của đơn hàng (customerId=" + order.getCustomerId() + ")");
        }

        // Đủ thông số đo (SPH, CYL, AXIS, PD)
        BigDecimal odSph = asBigDecimal(rxMap.get("odSph") != null ? rxMap.get("odSph") : rxMap.get("odSphere"));
        BigDecimal osSph = asBigDecimal(rxMap.get("osSph") != null ? rxMap.get("osSph") : rxMap.get("osSphere"));
        BigDecimal odCyl = asBigDecimal(rxMap.get("odCyl"));
        BigDecimal osCyl = asBigDecimal(rxMap.get("osCyl"));
        Integer odAxis = asInteger(rxMap.get("odAxis"));
        Integer osAxis = asInteger(rxMap.get("osAxis"));
        BigDecimal pd = asBigDecimal(rxMap.get("pd"));

        if (odSph == null || osSph == null || odCyl == null || osCyl == null || odAxis == null || osAxis == null || pd == null) {
            throw ApiException.of(ErrorCode.VALIDATION_ERROR,
                    "Toa kính thiếu thông số đo bắt buộc (SPH, CYL, AXIS, PD)");
        }

        CreateLabJobRequest.PrescriptionDto prescriptionDto = parsePrescriptionDto(rxMap, effectivePrescriptionId);

        // 3. Chuẩn bị danh sách yêu cầu gia công (Contract & Idempotency: LAB_REQ_{orderId}_{orderItemId})
        String frameSku = null;
        String frameName = null;
        String lensSku = null;
        String lensName = null;

        java.util.List<OrderItem> lensItems = new java.util.ArrayList<>();
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (isLensSku(item.getSkuId(), item.getProductName()) || item.getPrescriptionId() != null) {
                    lensItems.add(item);
                    if (lensSku == null) {
                        lensSku = item.getSkuId();
                        lensName = item.getProductName();
                    }
                } else if (frameSku == null) {
                    frameSku = item.getSkuId();
                    frameName = item.getProductName();
                }
            }
        }

        String trayNumber = "KHAY-" + (order.getOrderCode() != null ? order.getOrderCode() : order.getId());

        java.util.List<CreateLabJobRequest> requests = new java.util.ArrayList<>();
        if (lensItems.isEmpty()) {
            Long defaultItemId = 0L;
            String defaultSku = lensSku != null ? lensSku : "LENS-DEFAULT";
            requests.add(CreateLabJobRequest.builder()
                    .orderId(order.getId())
                    .orderItemId(defaultItemId)
                    .prescriptionId(effectivePrescriptionId)
                    .sku(defaultSku)
                    .idempotencyKey("LAB_REQ_" + order.getId() + "_" + defaultItemId)
                    .orderCode(order.getOrderCode())
                    .trayNumber(trayNumber)
                    .customerId(order.getCustomerId())
                    .customerName(order.getCustomerName())
                    .frameSku(frameSku)
                    .frameName(frameName)
                    .lensSku(defaultSku)
                    .lensName(lensName)
                    .technicianName(order.getStaffName())
                    .prescription(prescriptionDto)
                    .odSphere(odSph)
                    .odCyl(odCyl)
                    .odAxis(odAxis)
                    .osSphere(osSph)
                    .osCyl(osCyl)
                    .osAxis(osAxis)
                    .pd(pd)
                    .build());
        } else {
            for (OrderItem item : lensItems) {
                Long itemId = item.getId() != null ? item.getId() : 0L;
                requests.add(CreateLabJobRequest.builder()
                        .orderId(order.getId())
                        .orderItemId(itemId)
                        .prescriptionId(effectivePrescriptionId)
                        .sku(item.getSkuId())
                        .idempotencyKey("LAB_REQ_" + order.getId() + "_" + itemId)
                        .orderCode(order.getOrderCode())
                        .trayNumber(trayNumber)
                        .customerId(order.getCustomerId())
                        .customerName(order.getCustomerName())
                        .frameSku(frameSku)
                        .frameName(frameName)
                        .lensSku(item.getSkuId())
                        .lensName(item.getProductName())
                        .technicianName(order.getStaffName())
                        .prescription(prescriptionDto)
                        .odSphere(odSph)
                        .odCyl(odCyl)
                        .odAxis(odAxis)
                        .osSphere(osSph)
                        .osCyl(osCyl)
                        .osAxis(osAxis)
                        .pd(pd)
                        .build());
            }
        }

        // 4. Gọi liên service: Thêm retry tối đa 3 lần với Exponential Backoff khi gọi sang Lab gặp lỗi mạng/timeout
        int maxAttempts = 3;
        long initialBackoffMs = 100L;
        boolean allSuccess = true;
        Exception lastException = null;

        for (CreateLabJobRequest req : requests) {
            boolean reqSuccess = false;
            long backoff = initialBackoffMs;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    if (labServiceClient != null) {
                        labServiceClient.createLabJob(req);
                    } else if (labClient != null) {
                        Map<String, Object> fallbackBody = new LinkedHashMap<>();
                        fallbackBody.put("orderId", req.getOrderId());
                        fallbackBody.put("prescriptionId", req.getPrescriptionId());
                        fallbackBody.put("frameSku", req.getFrameSku());
                        fallbackBody.put("frameName", req.getFrameName());
                        fallbackBody.put("lensSku", req.getLensSku());
                        fallbackBody.put("lensName", req.getLensName());
                        fallbackBody.put("technicianName", req.getTechnicianName());
                        labClient.createFromOrder(fallbackBody);
                    }
                    reqSuccess = true;
                    log.info("Dispatch lab job thành công cho đơn {}, key: {} (lần thử {})",
                            order.getOrderCode(), req.getIdempotencyKey(), attempt);
                    break;
                } catch (Exception ex) {
                    lastException = ex;
                    log.warn("Lần thử {}/{} gọi sang Lab thất bại cho đơn {}, key: {}: {}",
                            attempt, maxAttempts, order.getOrderCode(), req.getIdempotencyKey(), ex.getMessage());
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(backoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        backoff *= 2; // Exponential Backoff: 100ms -> 200ms -> 400ms
                    }
                }
            }
            if (!reqSuccess) {
                allSuccess = false;
                break;
            }
        }

        // Nếu thất bại sau 3 lần, cập nhật trạng thái đơn là LAB_DISPATCH_FAILED (không rollback thanh toán gốc)
        if (!allSuccess) {
            log.error("Dispatch sang Lab thất bại sau {} lần thử cho đơn {}. Cập nhật trạng thái LAB_DISPATCH_FAILED",
                    maxAttempts, order.getOrderCode(), lastException);
            try {
                return orderStatusService.transition(order, OrderStatus.LAB_DISPATCH_FAILED, "SYSTEM",
                        "Gửi lệnh gia công sang Lab thất bại sau " + maxAttempts + " lần thử: "
                                + (lastException != null ? lastException.getMessage() : "Timeout/Network error"));
            } catch (Exception ex) {
                log.error("Không thể cập nhật trạng thái LAB_DISPATCH_FAILED cho đơn {}: {}",
                        order.getOrderCode(), ex.getMessage());
            }
        }

        if (allSuccess && order.currentStatus() == OrderStatus.LAB_DISPATCH_FAILED) {
            return orderStatusService.transition(order, OrderStatus.PAID, "SYSTEM", "Đã gửi lại lệnh gia công sang Lab");
        }
        return order;
    }

    private boolean isLensSku(String sku, String productName) {
        String cleanSku = sku == null ? "" : sku.toUpperCase();
        if (cleanSku.startsWith("LNS-") || cleanSku.startsWith("LZ-") || cleanSku.contains("LENS")) {
            return true;
        }
        return productName != null && productName.toLowerCase().contains("tròng");
    }

    private CreateLabJobRequest.PrescriptionDto parsePrescriptionDto(Map<?, ?> map, Long rxId) {
        return CreateLabJobRequest.PrescriptionDto.builder()
                .id(rxId)
                .customerId(asLong(map.get("customerId")))
                .odSphere(asBigDecimal(map.get("odSph") != null ? map.get("odSph") : map.get("odSphere")))
                .odCyl(asBigDecimal(map.get("odCyl")))
                .odAxis(asInteger(map.get("odAxis")))
                .odAdd(asBigDecimal(map.get("odAdd")))
                .osSphere(asBigDecimal(map.get("osSph") != null ? map.get("osSph") : map.get("osSphere")))
                .osCyl(asBigDecimal(map.get("osCyl")))
                .osAxis(asInteger(map.get("osAxis")))
                .osAdd(asBigDecimal(map.get("osAdd")))
                .pd(asBigDecimal(map.get("pd")))
                .notes(map.get("notes") != null ? map.get("notes").toString() : null)
                .build();
    }

    private Long asLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Long l) return l;
        if (obj instanceof Number num) return num.longValue();
        try {
            return Long.valueOf(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal asBigDecimal(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BigDecimal bd) return bd;
        if (obj instanceof Number num) return BigDecimal.valueOf(num.doubleValue());
        try {
            return new BigDecimal(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer asInteger(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Integer i) return i;
        if (obj instanceof Number num) return num.intValue();
        try {
            return Integer.valueOf(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.of(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Thiếu header Idempotency-Key cho yêu cầu tạo đơn");
        }
        return key.trim();
    }

    private String nextOrderCode() {
        String datePart = java.time.LocalDate.now().format(CODE_DATE);
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "ORD-" + datePart + "-"
                    + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
            if (orderRepository.findByOrderCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw ApiException.of(ErrorCode.CONFLICT, "Không sinh được mã đơn hàng duy nhất");
    }

    public record CheckoutOutcome(Order order, boolean replayed) {
    }

    static boolean totalsConsistent(Order order) {
        BigDecimal sum = order.getItems().stream()
                .map(OrderItem::getSubtotal)
                .reduce(BasketPricingService.ZERO, BigDecimal::add);
        return sum.compareTo(order.getTotalAmount()) == 0;
    }
}
