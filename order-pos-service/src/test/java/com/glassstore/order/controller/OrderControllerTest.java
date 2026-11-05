package com.glassstore.order.controller;

import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.OrderStatusHistoryRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.service.OrderCheckoutService;
import com.glassstore.order.service.OrderStatusService;
import com.glassstore.order.service.OrderService;
import com.glassstore.order.service.OrderCancellationService;
import com.glassstore.order.service.StockDeductionService;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderControllerTest {

    @Test
    void posVietQrCheckoutMarksOrderPaidAfterCounterConfirmation() {
        OrderRepository orderRepository = mock(OrderRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        OrderStatusHistoryRepository historyRepository = mock(OrderStatusHistoryRepository.class);
        OrderCheckoutService checkoutService = mock(OrderCheckoutService.class);
        OrderStatusService orderStatusService = mock(OrderStatusService.class);
        OrderService posService = mock(OrderService.class);
        OrderController controller = new OrderController(posService, mock(OrderCancellationService.class),
                mock(StockDeductionService.class), orderRepository, paymentRepository,
                historyRepository, checkoutService, orderStatusService);

        Order pendingOrder = Order.builder()
                .id(12L)
                .orderCode("ORD-POS-12")
                .paymentMethod(PaymentMethod.VIETQR)
                .status(OrderStatus.PENDING_PAYMENT)
                .build();
        Order paidOrder = Order.builder()
                .id(12L)
                .orderCode("ORD-POS-12")
                .paymentMethod(PaymentMethod.VIETQR)
                .status(OrderStatus.PAID)
                .build();
        OrderDtos.CheckoutRequest request = OrderDtos.CheckoutRequest.builder()
                .customerName("Khách vãng lai")
                .paymentMethod("VIETQR")
                .channel("POS")
                .staffName("Thu ngân")
                .build();
        when(posService.createCounterSale(request, "pos-request-12", "Bearer staff"))
                .thenReturn(ResponseEntity.status(201).body(paidOrder));

        var response = controller.createOrder("pos-request-12", request, "Bearer staff");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().getData().getStatus()).isEqualTo(OrderStatus.PAID);
        verify(posService).createCounterSale(request, "pos-request-12", "Bearer staff");
        verifyNoInteractions(checkoutService);
    }

    @Test
    void onlineCheckoutKeepsTheTeamCheckoutAndReplayContract() {
        OrderCheckoutService checkout = mock(OrderCheckoutService.class);
        OrderService pos = mock(OrderService.class);
        OrderController controller = new OrderController(pos, mock(OrderCancellationService.class),
                mock(StockDeductionService.class), mock(OrderRepository.class), mock(PaymentRepository.class),
                mock(OrderStatusHistoryRepository.class), checkout, mock(OrderStatusService.class));
        OrderDtos.CheckoutRequest request = OrderDtos.CheckoutRequest.builder().channel("ONLINE")
                .customerName("Online customer").paymentMethod("VIETQR").build();
        Order order = Order.builder().id(20L).build();
        when(checkout.checkout(request, "online-replay")).thenReturn(new OrderCheckoutService.CheckoutOutcome(order, true));
        var response = controller.createOrder("online-replay", request, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getData()).isSameAs(order);
        verifyNoInteractions(pos);
    }

    @Test
    void legacyPosJsonReachesSharedDtoWithoutLosingPrescriptionOrBenefits() throws Exception {
        OrderService pos = mock(OrderService.class);
        OrderController controller = new OrderController(pos, mock(OrderCancellationService.class),
                mock(StockDeductionService.class), mock(OrderRepository.class), mock(PaymentRepository.class),
                mock(OrderStatusHistoryRepository.class), mock(OrderCheckoutService.class), mock(OrderStatusService.class));
        when(pos.createCounterSale(any(), eq("key"), eq("Bearer staff")))
                .thenReturn(ResponseEntity.status(201).body(Order.builder().id(12L).status(OrderStatus.PAID).build()));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                .header("Idempotency-Key", "key").header("Authorization", "Bearer staff")
                .contentType("application/json").content("""
                {"channel":"POS","customerName":"QA","paymentMethod":"CASH","branchCode":"BRANCH_01",
                 "voucherCode":"QA10","pointsToUse":50,"cashReceived":2000000,
                 "items":[{"skuId":"LNS-QA","productName":"Lens","quantity":1,"prescriptionId":7,"unitPrice":500000}]}
                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("PAID"));
        var captor = org.mockito.ArgumentCaptor.forClass(OrderDtos.CheckoutRequest.class);
        verify(pos).createCounterSale(captor.capture(), eq("key"), eq("Bearer staff"));
        assertThat(captor.getValue().getPrescriptionId()).isEqualTo(7L);
        assertThat(captor.getValue().getItems().get(0).getSku()).isEqualTo("LNS-QA");
        assertThat(captor.getValue().getItems().get(0).getPrescriptionId()).isEqualTo(7L);
        assertThat(captor.getValue().getPointsToUse()).isEqualTo(50L);
        assertThat(captor.getValue().getCashReceived()).isEqualByComparingTo("2000000");
        assertThat(captor.getValue().getItems().get(0).getExpectedUnitPrice()).isEqualByComparingTo("500000");
    }
}
