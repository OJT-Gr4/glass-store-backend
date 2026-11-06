package com.glassstore.order.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.client.LabClient;
import com.glassstore.order.client.LabServiceClient;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.OrderType;
import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.dto.CreateLabJobRequest;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderItem;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderCheckoutServiceTest {

    private OrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private BasketPricingService pricingService;
    private OrderStatusService orderStatusService;
    private OrderInventoryService inventoryService;
    private LabClient labClient;
    private LabServiceClient labServiceClient;
    private WarrantyActivationService warrantyActivationService;
    private OrderCheckoutService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        pricingService = mock(BasketPricingService.class);
        orderStatusService = mock(OrderStatusService.class);
        inventoryService = mock(OrderInventoryService.class);
        labClient = mock(LabClient.class);
        labServiceClient = mock(LabServiceClient.class);
        warrantyActivationService = mock(WarrantyActivationService.class);
        CheckoutIdempotencyService idempotency = mock(CheckoutIdempotencyService.class);
        when(idempotency.fingerprint(any(OrderDtos.CheckoutRequest.class))).thenReturn("hash");
        when(idempotency.begin(any(),any(),any())).thenAnswer(inv -> new com.glassstore.order.entity.CheckoutRequestRecord());
        service = new OrderCheckoutService(orderRepository, paymentRepository, pricingService,
                orderStatusService, inventoryService, labClient, warrantyActivationService, labServiceClient,idempotency,mock(LabDispatchService.class));

        when(orderRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(orderRepository.findByOrderCode(any())).thenReturn(Optional.empty());
        AtomicLong ids = new AtomicLong(1);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            if (order.getId() == null) {
                order.setId(ids.getAndIncrement());
            }
            return order;
        });
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pricingService.price(any(), any(), any())).thenReturn(basket());
    }

    @Test
    @DisplayName("Online checkout requires a non-blank phone number (BR-16)")
    void onlinePhoneRequired() {
        OrderDtos.CheckoutRequest request = validRequest();
        request.setCustomerPhone(" ");
        assertThatThrownBy(() -> service.checkout(request, "k"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("Pos checkout falls back to placeholder phone when blank")
    void posPhoneFallback() {
        OrderDtos.CheckoutRequest request = validRequest();
        request.setChannel("POS");
        request.setCustomerPhone("");
        OrderCheckoutService.CheckoutOutcome outcome = service.checkout(request, "k");
        assertThat(outcome.order().getCustomerPhone()).isEqualTo("0000000000");
    }

    @Test
    @DisplayName("Prescription is mandatory for prescription-bearing orders (BR-03)")
    void prescriptionRequired() {
        OrderDtos.CheckoutRequest request = validRequest();
        request.setOrderType("COMPLETE_GLASSES");
        request.setPrescriptionId(null);
        assertThatThrownBy(() -> service.checkout(request, "k"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PRESCRIPTION_INCOMPLETE);
    }

    @Test
    @DisplayName("Cash settlement cannot run twice (BR-15)")
    void cashAlreadySettled() {
        Order order = Order.builder().id(1L).orderCode("ORD-X").paymentMethod(PaymentMethod.CASH)
                .status(OrderStatus.PENDING_PAYMENT).build();
        when(orderRepository.lockById(1L)).thenReturn(Optional.of(order));
        when(paymentRepository.existsByOrderIdAndStatus(1L, PaymentStatus.SUCCESS)).thenReturn(true);

        assertThat(service.settleCash(1L,"STAFF")).isSameAs(order);
        verify(paymentRepository,never()).save(any());
    }

    @Test
    @DisplayName("dispatchLabJobIfNeeded: Tự động gửi CreateLabJobRequest qua LabServiceClient khi đơn có kính thuốc")
    void dispatchLabJob_PrescriptionOrder_CallsFeignClient() {
        Map<String, Object> rxData = Map.of(
                "id", 55L,
                "customerId", 10L,
                "odSph", -2.00,
                "osSph", -2.00,
                "odCyl", -0.50,
                "osCyl", -0.50,
                "odAxis", 90,
                "osAxis", 90,
                "pd", 63.0
        );
        when(labServiceClient.getPrescriptionById(55L)).thenReturn(org.springframework.http.ResponseEntity.ok(Map.of("data", rxData)));

        List<OrderItem> items = new ArrayList<>();
        items.add(OrderItem.builder().skuId("LNS-CRIZAL-160").productName("Tròng Crizal").quantity(1).build());
        items.add(OrderItem.builder().skuId("FRM-RB-3025").productName("Gọng RayBan").quantity(1).build());

        Order order = Order.builder()
                .id(101L)
                .orderCode("ORD-20261104-001")
                .customerId(10L)
                .customerName("Nguyễn Văn A")
                .staffName("KTV Hưng")
                .status(OrderStatus.PAID)
                .orderType(OrderType.COMPLETE_GLASSES)
                .prescriptionId(55L)
                .items(items)
                .build();

        service.dispatchLabJobIfNeeded(order);

        ArgumentCaptor<CreateLabJobRequest> captor = ArgumentCaptor.forClass(CreateLabJobRequest.class);
        verify(labServiceClient).createLabJob(captor.capture());

        CreateLabJobRequest captured = captor.getValue();
        assertThat(captured.getOrderId()).isEqualTo(101L);
        assertThat(captured.getOrderCode()).isEqualTo("ORD-20261104-001");
        assertThat(captured.getTrayNumber()).isEqualTo("KHAY-ORD-20261104-001");
        assertThat(captured.getCustomerName()).isEqualTo("Nguyễn Văn A");
        assertThat(captured.getPrescriptionId()).isEqualTo(55L);
        assertThat(captured.getLensSku()).isEqualTo("LNS-CRIZAL-160");
        assertThat(captured.getFrameSku()).isEqualTo("FRM-RB-3025");
        assertThat(captured.getTechnicianName()).isEqualTo("KTV Hưng");
    }

    @Test
    @DisplayName("dispatchLabJobIfNeeded: Khi LabServiceClient gặp lỗi thì try-catch không ném lỗi ra ngoài")
    void dispatchLabJob_WhenFeignClientFails_DoesNotThrow() {
        Map<String, Object> rxData = Map.of(
                "id", 60L,
                "customerId", 20L,
                "odSph", -1.50,
                "osSph", -1.50,
                "odCyl", 0.0,
                "osCyl", 0.0,
                "odAxis", 0,
                "osAxis", 0,
                "pd", 62.0
        );
        when(labServiceClient.getPrescriptionById(60L)).thenReturn(org.springframework.http.ResponseEntity.ok(Map.of("data", rxData)));

        List<OrderItem> items = new ArrayList<>();
        items.add(OrderItem.builder().skuId("LNS-01").productName("Tròng lọc ánh sáng xanh").quantity(1).build());

        Order order = Order.builder()
                .id(102L)
                .orderCode("ORD-20261104-002")
                .customerId(20L)
                .customerName("Trần Thị B")
                .status(OrderStatus.PAID)
                .orderType(OrderType.LENS_ONLY)
                .prescriptionId(60L)
                .items(items)
                .build();

        doThrow(new RuntimeException("Lab service connection timed out"))
                .when(labServiceClient).createLabJob(any());

        assertThatCode(() -> service.dispatchLabJobIfNeeded(order)).doesNotThrowAnyException();
        verify(labServiceClient, atLeastOnce()).createLabJob(any());
    }

    @Test
    @DisplayName("dispatchLabJobIfNeeded: Đơn hàng không có kính thuốc/toa kính thì không gọi LabServiceClient")
    void dispatchLabJob_NonPrescriptionOrder_DoesNotCallFeignClient() {
        List<OrderItem> items = new ArrayList<>();
        items.add(OrderItem.builder().skuId("ACC-CASE-01").productName("Hộp kính").quantity(1).build());

        Order order = Order.builder()
                .id(103L)
                .orderCode("ORD-20261104-003")
                .customerName("Lê Văn C")
                .orderType(OrderType.ACCESSORY)
                .prescriptionId(null)
                .items(items)
                .build();

        service.dispatchLabJobIfNeeded(order);

        verify(labServiceClient, never()).createLabJob(any());
    }

    private static OrderDtos.CheckoutRequest validRequest() {
        return OrderDtos.CheckoutRequest.builder()
                .customerName("A")
                .customerPhone("090")
                .customerId(5L)
                .items(List.of(OrderDtos.CheckoutLine.builder()
                        .sku("SKU-1").quantity(1).expectedUnitPrice(new BigDecimal("10.00")).build()))
                .paymentMethod("CASH")
                .channel("ONLINE")
                .orderType("ACCESSORY")
                .build();
    }

    private static OrderDtos.PricedBasket basket() {
        return OrderDtos.PricedBasket.builder()
                .lines(List.of(OrderDtos.OrderItemSnapshot.builder()
                        .sku("SKU-1").productName("Item").quantity(1)
                        .unitPrice(new BigDecimal("10.00"))
                        .subtotal(new BigDecimal("10.00"))
                        .build()))
                .totalAmount(new BigDecimal("10.00"))
                .discountAmount(BigDecimal.ZERO.setScale(2))
                .finalAmount(new BigDecimal("10.00"))
                .priceChanges(List.of())
                .build();
    }
}
