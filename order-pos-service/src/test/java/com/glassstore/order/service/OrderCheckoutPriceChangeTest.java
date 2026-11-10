package com.glassstore.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.CheckoutRequestRecord;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.CheckoutRequestRepository;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test kiểm chứng nghiệp vụ giỏ hàng mở khi giá thay đổi (AC3 / FR-PRO-08 / FR-PRO-09).
 * Sử dụng dynamic proxies/stubs đảm bảo test chạy trong vài mili-giây, độc lập môi trường sandbox.
 */
class OrderCheckoutPriceChangeTest {

    private OrderCheckoutService checkoutService;
    private OrderDtos.PricedBasket mockBasket;

    @BeforeEach
    void setUp() {
        AtomicLong orderIdGen = new AtomicLong(1);

        OrderRepository orderRepo = (OrderRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{OrderRepository.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("findByIdempotencyKey".equals(name) || "findByOrderCode".equals(name)) {
                        return Optional.empty();
                    }
                    if ("save".equals(name)) {
                        Order o = (Order) args[0];
                        if (o.getId() == null) o.setId(orderIdGen.getAndIncrement());
                        return o;
                    }
                    return null;
                });

        PaymentRepository paymentRepo = (PaymentRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PaymentRepository.class},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        return args[0];
                    }
                    return null;
                });

        CheckoutRequestRepository requestRepo = (CheckoutRequestRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{CheckoutRequestRepository.class},
                (proxy, method, args) -> null);

        BasketPricingService pricingService = new BasketPricingService(null, null) {
            @Override
            public OrderDtos.PricedBasket price(List<OrderDtos.CheckoutLine> lines, String voucherCode, SalesChannel channel) {
                return mockBasket;
            }
        };

        CheckoutIdempotencyService idempotencyService = new CheckoutIdempotencyService(requestRepo, new ObjectMapper()) {
            @Override
            public String fingerprint(OrderDtos.CheckoutRequest request) {
                return "test-hash";
            }

            @Override
            public CheckoutRequestRecord begin(String key, String hash, String owner) {
                return new CheckoutRequestRecord();
            }
        };

        OrderInventoryService inventoryService = new OrderInventoryService(null, null, null) {
            @Override
            public void reserve(Order order) {
            }
        };

        checkoutService = new OrderCheckoutService(
                orderRepo,
                paymentRepo,
                pricingService,
                null,
                inventoryService,
                null,
                null,
                null,
                idempotencyService,
                null
        );
    }

    @Test
    @DisplayName("Giá sản phẩm thay đổi khi giỏ hàng đang mở & acceptPriceChange=false -> Ném ORDER_PRICE_CHANGED (AC3)")
    void checkout_PriceChangedWhileCartOpen_ThrowsOrderPriceChanged() {
        OrderDtos.PriceChange change = OrderDtos.PriceChange.builder()
                .sku("SKU-BOLON-01")
                .expectedUnitPrice(new BigDecimal("1000000.00"))
                .currentUnitPrice(new BigDecimal("1200000.00"))
                .build();

        mockBasket = OrderDtos.PricedBasket.builder()
                .lines(List.of(OrderDtos.OrderItemSnapshot.builder()
                        .sku("SKU-BOLON-01")
                        .productName("Gọng Bolon")
                        .quantity(1)
                        .unitPrice(new BigDecimal("1200000.00"))
                        .subtotal(new BigDecimal("1200000.00"))
                        .build()))
                .totalAmount(new BigDecimal("1200000.00"))
                .discountAmount(BigDecimal.ZERO.setScale(2))
                .finalAmount(new BigDecimal("1200000.00"))
                .priceChanges(List.of(change))
                .build();

        OrderDtos.CheckoutRequest request = OrderDtos.CheckoutRequest.builder()
                .customerName("Nguyễn Văn A")
                .customerPhone("0987654321")
                .customerId(10L)
                .channel("ONLINE")
                .orderType("ACCESSORY")
                .paymentMethod("CASH")
                .acceptPriceChange(false)
                .items(List.of(OrderDtos.CheckoutLine.builder()
                        .sku("SKU-BOLON-01")
                        .quantity(1)
                        .expectedUnitPrice(new BigDecimal("1000000.00"))
                        .build()))
                .build();

        assertThatThrownBy(() -> checkoutService.checkout(request, "key-cart-123"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.ORDER_PRICE_CHANGED);
                    assertThat(apiEx.getMessage()).contains("Giá sản phẩm đã thay đổi");
                });
    }

    @Test
    @DisplayName("Giá sản phẩm thay đổi nhưng khách xác nhận acceptPriceChange=true -> Đặt hàng thành công theo giá mới (AC3)")
    void checkout_PriceChangedWithAcceptPriceChange_SucceedsWithNewPrice() {
        OrderDtos.PriceChange change = OrderDtos.PriceChange.builder()
                .sku("SKU-BOLON-01")
                .expectedUnitPrice(new BigDecimal("1000000.00"))
                .currentUnitPrice(new BigDecimal("1200000.00"))
                .build();

        mockBasket = OrderDtos.PricedBasket.builder()
                .lines(List.of(OrderDtos.OrderItemSnapshot.builder()
                        .sku("SKU-BOLON-01")
                        .productName("Gọng Bolon")
                        .quantity(1)
                        .unitPrice(new BigDecimal("1200000.00"))
                        .subtotal(new BigDecimal("1200000.00"))
                        .build()))
                .totalAmount(new BigDecimal("1200000.00"))
                .discountAmount(BigDecimal.ZERO.setScale(2))
                .finalAmount(new BigDecimal("1200000.00"))
                .priceChanges(List.of(change))
                .build();

        OrderDtos.CheckoutRequest request = OrderDtos.CheckoutRequest.builder()
                .customerName("Nguyễn Văn A")
                .customerPhone("0987654321")
                .customerId(10L)
                .channel("ONLINE")
                .orderType("ACCESSORY")
                .paymentMethod("CASH")
                .acceptPriceChange(true) // Khách đã xác nhận chấp nhận giá mới
                .items(List.of(OrderDtos.CheckoutLine.builder()
                        .sku("SKU-BOLON-01")
                        .quantity(1)
                        .expectedUnitPrice(new BigDecimal("1000000.00"))
                        .build()))
                .build();

        OrderCheckoutService.CheckoutOutcome outcome = checkoutService.checkout(request, "key-cart-456");

        assertThat(outcome).isNotNull();
        assertThat(outcome.order()).isNotNull();
        assertThat(outcome.order().getTotalAmount()).isEqualByComparingTo("1200000.00");
        assertThat(outcome.order().getCustomerName()).isEqualTo("Nguyễn Văn A");
    }
}
