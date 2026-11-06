package com.glassstore.order.config;

import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.OrderType;
import com.glassstore.order.domain.PaymentMethod;
import com.glassstore.order.domain.PaymentStatus;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.OrderItem;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "glassstore.demo.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class OrderDataInitializer implements CommandLineRunner {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    @Override
    public void run(String... args) {
        if (orderRepository.count() > 0) {
            log.info("Order database đã có sẵn {} đơn hàng từ CSDL.", orderRepository.count());
            return;
        }

        log.info("Khởi tạo seed data 5 đơn hàng chuỗi (OMS) đa kênh cho db_order...");

        // 1. Đơn POS Đã thanh toán (PAID)
        Order o1 = Order.builder()
                .orderCode("OPT-POS-2026-001")
                .customerId(10L)
                .customerName("Nguyễn Hoàng Duy")
                .customerPhone("0918732998")
                .channel(SalesChannel.POS)
                .branchName("Flagship Q.1 (TP.HCM)")
                .staffName("Nguyễn Văn Nam (POS)")
                .orderType(OrderType.COMPLETE_GLASSES)
                .totalAmount(new BigDecimal("3900000"))
                .discountAmount(new BigDecimal("200000"))
                .taxAmount(new BigDecimal("296000"))
                .finalAmount(new BigDecimal("3996000"))
                .status(OrderStatus.PAID)
                .paymentMethod(PaymentMethod.VIETQR)
                .notes("Khách đặt trực tiếp tại quầy Flagship, đã thanh toán qua mã QR VietQR.")
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.now().minusHours(4))
                .updatedAt(LocalDateTime.now().minusHours(4))
                .build();

        OrderItem item1_1 = OrderItem.builder().order(o1).skuId("FRM-BOLON-BLK").productName("Gọng Bolon Titanium - Đen Nhám").quantity(1).unitPrice(new BigDecimal("3250000")).subtotal(new BigDecimal("3250000")).build();
        OrderItem item1_2 = OrderItem.builder().order(o1).skuId("LNS-CHEMI-160").productName("Cặp Tròng Chemi U2 BlueCut 1.60").quantity(1).unitPrice(new BigDecimal("650000")).subtotal(new BigDecimal("650000")).build();
        o1.getItems().add(item1_1);
        o1.getItems().add(item1_2);

        // 2. Đơn Online Đang mài lắp xưởng Lab (PROCESSING -> PREPARING)
        Order o2 = Order.builder()
                .orderCode("OPT-ONL-2026-002")
                .customerId(11L)
                .customerName("Lê Thúy Kiều")
                .customerPhone("0988124551")
                .shippingAddress("120 Hai Bà Trưng, P. Đa Kao, Quận 1, TP.HCM")
                .channel(SalesChannel.ONLINE)
                .branchName("Trung Tâm Kho & Xưởng Lab TP.HCM")
                .staffName("Hệ Thống Online")
                .orderType(OrderType.COMPLETE_GLASSES)
                .totalAmount(new BigDecimal("5230000"))
                .discountAmount(BigDecimal.ZERO)
                .taxAmount(new BigDecimal("418400"))
                .finalAmount(new BigDecimal("5648400"))
                .status(OrderStatus.PREPARING)
                .paymentMethod(PaymentMethod.CARD)
                .notes("Đơn đặt qua Web App, BS Tuyết đã ký duyệt toa kính, KTV Tuấn đang mài tròng.")
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.now().minusHours(8))
                .updatedAt(LocalDateTime.now().minusHours(2))
                .build();

        OrderItem item2_1 = OrderItem.builder().order(o2).skuId("FRM-GM-BLK").productName("Gọng Gentle Monster TR90 - Đen Bóng").quantity(1).unitPrice(new BigDecimal("1850000")).subtotal(new BigDecimal("1850000")).build();
        OrderItem item2_2 = OrderItem.builder().order(o2).skuId("LNS-ESS-TRANS").productName("Cặp Tròng Essilor Transitions Gen 8 1.56").quantity(1).unitPrice(new BigDecimal("3380000")).subtotal(new BigDecimal("3380000")).build();
        o2.getItems().add(item2_1);
        o2.getItems().add(item2_2);

        // 3. Đơn POS Đang giao hỏa tốc (SHIPPING)
        Order o3 = Order.builder()
                .orderCode("OPT-POS-2026-003")
                .customerId(12L)
                .customerName("Trịnh Minh Quang")
                .customerPhone("0912449002")
                .shippingAddress("Tòa Landmark 81, 720A Điện Biên Phủ, P.22, Bình Thạnh, TP.HCM")
                .channel(SalesChannel.POS)
                .branchName("Flagship Q.1 (TP.HCM)")
                .staffName("Nguyễn Văn Nam (POS)")
                .orderType(OrderType.FRAME_ONLY)
                .totalAmount(new BigDecimal("4950000"))
                .discountAmount(new BigDecimal("500000"))
                .taxAmount(new BigDecimal("356000"))
                .finalAmount(new BigDecimal("4806000"))
                .status(OrderStatus.SHIPPING)
                .carrierName("GHTK")
                .trackingCode("GHTK-HN-2026884")
                .paymentMethod(PaymentMethod.CASH)
                .notes("Giao hỏa tốc trong 2h cho khách hàng VIP.")
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.now().minusHours(12))
                .updatedAt(LocalDateTime.now().minusHours(1))
                .build();

        OrderItem item3_1 = OrderItem.builder().order(o3).skuId("SUN-RB-WAYF-BLK").productName("Kính Râm Ray-Ban Wayfarer Polarized").quantity(1).unitPrice(new BigDecimal("4950000")).subtotal(new BigDecimal("4950000")).build();
        o3.getItems().add(item3_1);

        // 4. Đơn Hoàn tất (COMPLETED)
        Order o4 = Order.builder()
                .orderCode("OPT-ONL-2026-004")
                .customerId(13L)
                .customerName("Phạm Thị Bích Ngọc")
                .customerPhone("0977898332")
                .shippingAddress("45 Hoàng Hoa Thám, Ba Đình, Hà Nội")
                .channel(SalesChannel.ONLINE)
                .branchName("Chi Nhánh Hoàn Kiếm (Hà Nội)")
                .staffName("Hệ Thống Online")
                .orderType(OrderType.ACCESSORY)
                .totalAmount(new BigDecimal("300000"))
                .discountAmount(BigDecimal.ZERO)
                .taxAmount(new BigDecimal("24000"))
                .finalAmount(new BigDecimal("324000"))
                .status(OrderStatus.COMPLETED)
                .carrierName("AhaMove")
                .trackingCode("AHA-SG-993214")
                .completedAt(LocalDateTime.now().minusDays(1))
                .paymentMethod(PaymentMethod.VIETQR)
                .notes("Khách đã nhận hàng và ký biên bản bàn giao thành công.")
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.now().minusDays(2))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();

        OrderItem item4_1 = OrderItem.builder().order(o4).skuId("ACC-BOX-NANO").productName("Combo Hộp Đựng Da + Khăn Nano").quantity(2).unitPrice(new BigDecimal("150000")).subtotal(new BigDecimal("300000")).build();
        o4.getItems().add(item4_1);

        // 5. Đơn Hủy (CANCELLED)
        Order o5 = Order.builder()
                .orderCode("OPT-ONL-2026-005")
                .customerId(14L)
                .customerName("Trần Văn An")
                .customerPhone("0905112233")
                .channel(SalesChannel.ONLINE)
                .branchName("Trung Tâm Kho Online")
                .staffName("Hệ Thống Online")
                .orderType(OrderType.FRAME_ONLY)
                .totalAmount(new BigDecimal("3250000"))
                .discountAmount(BigDecimal.ZERO)
                .taxAmount(new BigDecimal("260000"))
                .finalAmount(new BigDecimal("3510000"))
                .status(OrderStatus.CANCELLED)
                .cancelReason("Khách hàng đổi ý muốn chuyển sang đặt mẫu gọng Titan không viền.")
                .cancelledBy("Hệ Thống Online")
                .cancelledAt(LocalDateTime.now().minusDays(3))
                .paymentMethod(PaymentMethod.VIETQR)
                .notes("Khách hàng đổi ý muốn chuyển sang đặt mẫu gọng Titan không viền.")
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.now().minusDays(3))
                .updatedAt(LocalDateTime.now().minusDays(3))
                .build();

        OrderItem item5_1 = OrderItem.builder().order(o5).skuId("FRM-BOLON-SIL").productName("Gọng Bolon Titanium - Bạc Sang Trọng").quantity(1).unitPrice(new BigDecimal("3250000")).subtotal(new BigDecimal("3250000")).build();
        o5.getItems().add(item5_1);

        orderRepository.saveAll(List.of(o1, o2, o3, o4, o5));

        // Nạp payment records
        List<Order> savedOrders = orderRepository.findAll();
        for (Order so : savedOrders) {
            Payment p = Payment.builder()
                    .orderId(so.getId())
                    .paymentMethod(so.getPaymentMethod())
                    .amount(so.getFinalAmount())
                    .transactionCode("TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                    .idempotencyKey(UUID.randomUUID().toString())
                    .status(so.getStatus() == OrderStatus.CANCELLED ? PaymentStatus.REFUNDED : PaymentStatus.SUCCESS)
                    .paidAt(so.getCreatedAt())
                    .build();
            paymentRepository.save(p);
        }

        log.info("Nạp thành công 5 đơn hàng OMS đa trạng thái và thanh toán vào db_order!");
    }
}
