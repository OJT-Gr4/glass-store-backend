package com.glassstore.order.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.client.LabServiceClient;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.OrderType;
import com.glassstore.order.dto.CreateLabJobRequest;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderItem;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Kiểm thử Validation trước dispatch và Retry với Exponential Backoff tại order-pos-service.
 */
@ExtendWith(MockitoExtension.class)
class OrderLabDispatchValidationAndRetryTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private BasketPricingService pricingService;

    @Mock
    private OrderStatusService orderStatusService;

    @Mock
    private OrderInventoryService inventoryService;

    @Mock
    private WarrantyActivationService warrantyActivationService;

    @Mock
    private LabServiceClient labServiceClient;

    @InjectMocks
    private OrderCheckoutService checkoutService;

    private Order paidOrder;
    private Map<String, Object> completeRxData;

    @BeforeEach
    void setUp() {
        List<OrderItem> items = new ArrayList<>();
        items.add(OrderItem.builder()
                .id(11L)
                .skuId("LNS-ESSILOR-160")
                .productName("Tròng Kính Crizal 1.60")
                .quantity(1)
                .build());
        items.add(OrderItem.builder()
                .id(12L)
                .skuId("FRM-RAYBAN-5228")
                .productName("Gọng Kính RayBan")
                .quantity(1)
                .build());

        paidOrder = Order.builder()
                .id(100L)
                .orderCode("ORD-20261105-001")
                .customerId(42L)
                .customerName("Trần Văn Bảo")
                .staffName("KTV Cường")
                .status(OrderStatus.PAID)
                .orderType(OrderType.COMPLETE_GLASSES)
                .prescriptionId(88L)
                .items(items)
                .build();

        completeRxData = new HashMap<>();
        completeRxData.put("id", 88L);
        completeRxData.put("customerId", 42L);
        completeRxData.put("odSph", new BigDecimal("-2.75"));
        completeRxData.put("odCyl", new BigDecimal("-0.75"));
        completeRxData.put("odAxis", 95);
        completeRxData.put("osSph", new BigDecimal("-2.50"));
        completeRxData.put("osCyl", new BigDecimal("-0.50"));
        completeRxData.put("osAxis", 85);
        completeRxData.put("pd", new BigDecimal("63.5"));
    }

    @Nested
    @DisplayName("Yêu cầu 1 & Contract: Đơn hợp lệ -> Dispatch thành công kèm IdempotencyKey chuẩn")
    class ValidDispatchTests {

        @Test
        @DisplayName("Đơn PAID + đủ toa -> Dispatch thành công, gửi đúng format idempotencyKey LAB_REQ_{orderId}_{orderItemId}")
        void dispatchLabJob_PaidOrderAndCompleteRx_DispatchesSuccessfully() {
            when(labServiceClient.getPrescriptionById(88L))
                    .thenReturn(ResponseEntity.ok(Map.of("data", completeRxData)));

            checkoutService.dispatchLabJob(paidOrder);

            ArgumentCaptor<CreateLabJobRequest> captor = ArgumentCaptor.forClass(CreateLabJobRequest.class);
            verify(labServiceClient, times(1)).createLabJob(captor.capture());

            CreateLabJobRequest request = captor.getValue();
            assertThat(request.getOrderId()).isEqualTo(100L);
            assertThat(request.getOrderItemId()).isEqualTo(11L);
            assertThat(request.getPrescriptionId()).isEqualTo(88L);
            assertThat(request.getSku()).isEqualTo("LNS-ESSILOR-160");
            assertThat(request.getIdempotencyKey()).isEqualTo("LAB_REQ_100_11");
            assertThat(request.getTrayNumber()).isEqualTo("KHAY-ORD-20261105-001");
            assertThat(request.getCustomerId()).isEqualTo(42L);
            assertThat(request.getCustomerName()).isEqualTo("Trần Văn Bảo");
            assertThat(request.getOdSphere()).isEqualByComparingTo(new BigDecimal("-2.75"));
            assertThat(request.getPd()).isEqualByComparingTo(new BigDecimal("63.5"));
        }
    }

    @Nested
    @DisplayName("Yêu cầu 2: Pre-dispatch Validation - Chặn tạo việc và trả về HTTP 400")
    class PreDispatchValidationTests {

        @Test
        @DisplayName("Đơn chưa thanh toán (status != PAID) -> ném ApiException VALIDATION_ERROR (HTTP 400)")
        void dispatchLabJob_UnpaidOrder_ThrowsValidationError() {
            paidOrder.setStatus(OrderStatus.PENDING_PAYMENT);

            assertThatThrownBy(() -> checkoutService.dispatchLabJob(paidOrder))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });

            verify(labServiceClient, never()).createLabJob(any());
        }

        @Test
        @DisplayName("Đơn thiếu toa kính (prescriptionId == null) -> ném ApiException VALIDATION_ERROR (HTTP 400)")
        void dispatchLabJob_MissingPrescriptionId_ThrowsValidationError() {
            paidOrder.setPrescriptionId(null);
            paidOrder.getItems().forEach(i -> i.setPrescriptionId(null));

            assertThatThrownBy(() -> checkoutService.dispatchLabJob(paidOrder))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });

            verify(labServiceClient, never()).createLabJob(any());
        }

        @Test
        @DisplayName("Toa kính không tồn tại trên lab-service -> ném ApiException VALIDATION_ERROR (HTTP 400)")
        void dispatchLabJob_PrescriptionNotFound_ThrowsValidationError() {
            when(labServiceClient.getPrescriptionById(88L))
                    .thenThrow(new RuntimeException("404 Not Found"));

            assertThatThrownBy(() -> checkoutService.dispatchLabJob(paidOrder))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });

            verify(labServiceClient, never()).createLabJob(any());
        }

        @Test
        @DisplayName("Sai khách hàng (order.customerId != rx.customerId) -> ném ApiException VALIDATION_ERROR (HTTP 400)")
        void dispatchLabJob_MismatchedCustomer_ThrowsValidationError() {
            completeRxData.put("customerId", 999L); // Toa của khách hàng khác
            when(labServiceClient.getPrescriptionById(88L))
                    .thenReturn(ResponseEntity.ok(Map.of("data", completeRxData)));

            assertThatThrownBy(() -> checkoutService.dispatchLabJob(paidOrder))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });

            verify(labServiceClient, never()).createLabJob(any());
        }

        @Test
        @DisplayName("Thiếu thông số đo SPH -> ném ApiException VALIDATION_ERROR (HTTP 400)")
        void dispatchLabJob_MissingSph_ThrowsValidationError() {
            completeRxData.remove("odSph");
            when(labServiceClient.getPrescriptionById(88L))
                    .thenReturn(ResponseEntity.ok(Map.of("data", completeRxData)));

            assertThatThrownBy(() -> checkoutService.dispatchLabJob(paidOrder))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });

            verify(labServiceClient, never()).createLabJob(any());
        }

        @Test
        @DisplayName("Thiếu thông số đo PD -> ném ApiException VALIDATION_ERROR (HTTP 400)")
        void dispatchLabJob_MissingPd_ThrowsValidationError() {
            completeRxData.remove("pd");
            when(labServiceClient.getPrescriptionById(88L))
                    .thenReturn(ResponseEntity.ok(Map.of("data", completeRxData)));

            assertThatThrownBy(() -> checkoutService.dispatchLabJob(paidOrder))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException apiEx = (ApiException) ex;
                        assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(apiEx.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });

            verify(labServiceClient, never()).createLabJob(any());
        }
    }

    @Nested
    @DisplayName("Yêu cầu 3: Retry tối đa 3 lần với Exponential Backoff & chuyển trạng thái LAB_DISPATCH_FAILED")
    class RetryAndBackoffTests {

        @Test
        @DisplayName("Gặp lỗi mạng/timeout: retry tối đa 3 lần, nếu thất bại cả 3 lần thì cập nhật trạng thái đơn là LAB_DISPATCH_FAILED")
        void dispatchLabJob_RetryThreeTimes_UpdatesToLabDispatchFailed() {
            when(labServiceClient.getPrescriptionById(88L))
                    .thenReturn(ResponseEntity.ok(Map.of("data", completeRxData)));

            // Giả lập cả 3 lần gọi FeignClient đều bị timeout/lỗi mạng
            doThrow(new RuntimeException("ConnectTimeoutException: lab-service unreachable"))
                    .when(labServiceClient).createLabJob(any());

            checkoutService.dispatchLabJob(paidOrder);

            // Kiểm tra FeignClient được gọi đúng 3 lần (lần đầu + 2 lần retry)
            verify(labServiceClient, times(3)).createLabJob(any());

            // Đơn hàng được cập nhật trạng thái sang LAB_DISPATCH_FAILED
            verify(orderStatusService, times(1)).transition(
                    eq(paidOrder),
                    eq(OrderStatus.LAB_DISPATCH_FAILED),
                    eq("SYSTEM"),
                    contains("thất bại sau 3 lần thử")
            );
        }

        @Test
        @DisplayName("Lần 1 và 2 gặp lỗi mạng nhưng lần 3 thành công -> không cập nhật LAB_DISPATCH_FAILED")
        void dispatchLabJob_SucceedsOnThirdAttempt_DoesNotFail() {
            when(labServiceClient.getPrescriptionById(88L))
                    .thenReturn(ResponseEntity.ok(Map.of("data", completeRxData)));

            // Lần 1 và 2 lỗi, lần 3 thành công
            doThrow(new RuntimeException("Timeout"))
                    .doThrow(new RuntimeException("Timeout"))
                    .doReturn(ResponseEntity.ok().build())
                    .when(labServiceClient).createLabJob(any());

            checkoutService.dispatchLabJob(paidOrder);

            verify(labServiceClient, times(3)).createLabJob(any());
            verify(orderStatusService, never()).transition(any(Order.class), eq(OrderStatus.LAB_DISPATCH_FAILED), any(), any());
        }
    }
}
