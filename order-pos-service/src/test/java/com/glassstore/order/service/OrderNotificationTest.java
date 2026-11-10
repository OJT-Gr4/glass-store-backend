package com.glassstore.order.service;

import com.glassstore.order.client.IdentityNotificationClient;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.dto.NotificationEventPayload;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.OrderStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderNotificationTest {

    @Test
    void transitionToReadyForPickup_triggersOrderNotificationPublisher() {
        OrderRepository orderRepo = mock(OrderRepository.class);
        OrderStatusHistoryRepository historyRepo = mock(OrderStatusHistoryRepository.class);
        OrderNotificationPublisher publisher = mock(OrderNotificationPublisher.class);

        OrderStatusService service = new OrderStatusService(orderRepo, historyRepo);
        service.setOrderNotificationPublisher(publisher);

        Order order = Order.builder()
                .id(77L)
                .orderCode("ORD-77")
                .customerId(5L)
                .customerName("Nguyễn Duy")
                .customerPhone("0901112222")
                .totalAmount(BigDecimal.valueOf(1000000))
                .finalAmount(BigDecimal.valueOf(1000000))
                .status(OrderStatus.PREPARING)
                .build();

        when(orderRepo.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order saved = service.transition(order, OrderStatus.READY_FOR_PICKUP, "pos_staff", "Kính đã xong");

        assertThat(saved.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        verify(publisher).publishOrderStatus(saved, OrderStatus.READY_FOR_PICKUP);
    }

    @Test
    void orderNotificationPublisher_emitsForMilestonesAndSkipsNonMilestones() {
        IdentityNotificationClient client = mock(IdentityNotificationClient.class);
        OrderNotificationPublisher publisher = new OrderNotificationPublisher(client);

        Order order = Order.builder()
                .id(88L)
                .orderCode("ORD-88")
                .customerId(5L)
                .customerName("Nguyễn Duy")
                .branchName("Quận 1")
                .build();

        publisher.publishOrderStatus(order, OrderStatus.CONFIRMED);
        verify(client, never()).publishEvent(any());

        publisher.publishOrderStatus(order, OrderStatus.READY_FOR_PICKUP);
        ArgumentCaptor<NotificationEventPayload> captor = ArgumentCaptor.forClass(NotificationEventPayload.class);
        verify(client).publishEvent(captor.capture());

        NotificationEventPayload payload = captor.getValue();
        assertThat(payload.getEventKey()).isEqualTo("ORDER:88:STATUS:READY_FOR_PICKUP");
        assertThat(payload.getCategory()).isEqualTo("ORDER");
        assertThat(payload.getCustomerId()).isEqualTo(5L);
        assertThat(payload.getCustomerTitle()).contains("sẵn sàng nhận tại cửa hàng");
    }
}
