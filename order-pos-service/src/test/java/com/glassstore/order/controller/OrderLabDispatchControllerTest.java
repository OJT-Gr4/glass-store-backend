package com.glassstore.order.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.OrderStatusHistoryRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.service.OrderCancellationService;
import com.glassstore.order.service.OrderCheckoutService;
import com.glassstore.order.service.OrderService;
import com.glassstore.order.service.OrderStatusService;
import com.glassstore.order.service.StockDeductionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrderLabDispatchControllerTest {

    private OrderRepository orderRepository;
    private OrderCheckoutService checkoutService;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        checkoutService = mock(OrderCheckoutService.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        OrderStatusHistoryRepository historyRepository = mock(OrderStatusHistoryRepository.class);
        OrderStatusService orderStatusService = mock(OrderStatusService.class);
        OrderService posService = mock(OrderService.class);
        OrderCancellationService cancellations = mock(OrderCancellationService.class);
        StockDeductionService stockDeductionService = mock(StockDeductionService.class);

        controller = new OrderController(
                posService, cancellations, stockDeductionService,
                orderRepository, paymentRepository, historyRepository,
                checkoutService, orderStatusService);
    }

    @Test
    @DisplayName("POST /api/orders/{id}/dispatch-lab: Đơn thanh toán hợp lệ + đủ toa -> trả về 200 OK")
    void dispatchLabJob_PaidAndComplete_Returns200Ok() {
        Order paidOrder = Order.builder()
                .id(100L)
                .orderCode("ORD-100")
                .status(OrderStatus.PAID)
                .prescriptionId(50L)
                .build();

        when(orderRepository.findById(100L)).thenReturn(Optional.of(paidOrder));
        when(checkoutService.dispatchLabJob(paidOrder)).thenReturn(paidOrder);

        ResponseEntity<ApiResponse<Order>> response = controller.dispatchLabJob(100L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getOrderCode()).isEqualTo("ORD-100");
        verify(checkoutService, times(1)).dispatchLabJob(paidOrder);
    }

    @Test
    @DisplayName("POST /api/orders/{id}/dispatch-lab: Đơn chưa thanh toán -> ném ApiException VALIDATION_ERROR (HTTP 400)")
    void dispatchLabJob_UnpaidOrder_ThrowsValidationError() {
        Order unpaidOrder = Order.builder()
                .id(101L)
                .orderCode("ORD-101")
                .status(OrderStatus.PENDING_PAYMENT)
                .build();

        when(orderRepository.findById(101L)).thenReturn(Optional.of(unpaidOrder));
        when(checkoutService.dispatchLabJob(unpaidOrder))
                .thenThrow(ApiException.of(ErrorCode.VALIDATION_ERROR, "Đơn hàng phải ở trạng thái PAID để gửi lệnh gia công sang Lab"));

        assertThatThrownBy(() -> controller.dispatchLabJob(101L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("POST /api/orders/{id}/dispatch-lab: Thiếu toa hoặc sai khách hàng -> ném ApiException VALIDATION_ERROR (HTTP 400)")
    void dispatchLabJob_MissingPrescriptionOrWrongCustomer_ThrowsValidationError() {
        Order paidOrder = Order.builder()
                .id(102L)
                .orderCode("ORD-102")
                .status(OrderStatus.PAID)
                .prescriptionId(null)
                .build();

        when(orderRepository.findById(102L)).thenReturn(Optional.of(paidOrder));
        when(checkoutService.dispatchLabJob(paidOrder))
                .thenThrow(ApiException.of(ErrorCode.VALIDATION_ERROR, "Đơn hàng kính thuốc thiếu toa kính (prescriptionId)"));

        assertThatThrownBy(() -> controller.dispatchLabJob(102L))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }
}

