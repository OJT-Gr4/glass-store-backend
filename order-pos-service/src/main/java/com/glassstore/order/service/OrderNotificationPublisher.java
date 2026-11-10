package com.glassstore.order.service;

import com.glassstore.order.client.IdentityNotificationClient;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.dto.NotificationEventPayload;
import com.glassstore.order.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderNotificationPublisher {

    private static final Set<OrderStatus> MILESTONE_STATUSES = EnumSet.of(
            OrderStatus.PAID,
            OrderStatus.READY_FOR_PICKUP,
            OrderStatus.SHIPPING,
            OrderStatus.COMPLETED,
            OrderStatus.CANCEL_REQUESTED,
            OrderStatus.CANCELLED,
            OrderStatus.REFUND_PENDING,
            OrderStatus.REFUNDED
    );

    private final IdentityNotificationClient notificationClient;

    public void publishOrderStatus(Order order, OrderStatus target) {
        if (order == null || order.getId() == null || target == null || !MILESTONE_STATUSES.contains(target)) {
            return;
        }
        String code = order.getOrderCode() != null && !order.getOrderCode().isBlank()
                ? order.getOrderCode().trim()
                : "ORD-" + order.getId();
        String customerName = order.getCustomerName() != null && !order.getCustomerName().isBlank()
                ? order.getCustomerName().trim()
                : "Khách hàng";

        String customerTitle;
        String customerMessage;
        String staffTitle;
        String staffMessage;

        switch (target) {
            case PAID -> {
                customerTitle = "Thanh toán đơn hàng " + code + " thành công";
                customerMessage = "Hệ thống đã ghi nhận thanh toán cho đơn " + code + ".";
                staffTitle = "Đơn hàng " + code + " đã thanh toán";
                staffMessage = "Khách hàng " + customerName + " vừa hoàn tất thanh toán đơn " + code + ".";
            }
            case READY_FOR_PICKUP -> {
                customerTitle = "Đơn hàng " + code + " sẵn sàng nhận tại cửa hàng!";
                customerMessage = "Đơn " + code + " đã sẵn sàng để bạn đến nhận tại quầy"
                        + (order.getBranchName() != null && !order.getBranchName().isBlank() ? " (" + order.getBranchName() + ")" : "") + ".";
                staffTitle = "Đơn hàng " + code + " sẵn sàng bàn giao tại quầy";
                staffMessage = "Khách hàng: " + customerName + " - Sẵn sàng bàn giao.";
            }
            case SHIPPING -> {
                String tracking = order.getTrackingCode() != null && !order.getTrackingCode().isBlank()
                        ? " (Vận đơn: " + order.getTrackingCode() + ")"
                        : "";
                customerTitle = "Đơn hàng " + code + " đang được giao";
                customerMessage = "Đơn " + code + " đã xuất kho giao cho đơn vị vận chuyển" + tracking + ".";
                staffTitle = "Đơn hàng " + code + " đang giao hàng";
                staffMessage = "Đơn của " + customerName + " đã chuyển SHIPPING" + tracking + ".";
            }
            case COMPLETED -> {
                customerTitle = "Đơn hàng " + code + " đã hoàn tất";
                customerMessage = "Cảm ơn bạn đã mua sắm tại OptiVision! Đơn " + code + " đã hoàn thành.";
                staffTitle = "Đơn hàng " + code + " hoàn tất";
                staffMessage = "Đơn " + code + " (" + customerName + ") đã bàn giao thành công.";
            }
            case CANCEL_REQUESTED -> {
                customerTitle = "Đã tiếp nhận yêu cầu hủy đơn " + code;
                customerMessage = "Yêu cầu hủy đơn " + code + " đang được nhân viên kiểm tra xử lý.";
                staffTitle = "Khách yêu cầu hủy đơn " + code;
                staffMessage = customerName + " vừa gửi yêu cầu hủy đơn " + code + ".";
            }
            case CANCELLED -> {
                customerTitle = "Đơn hàng " + code + " đã được hủy";
                customerMessage = "Đơn hàng " + code + " đã chuyển trạng thái Đã hủy.";
                staffTitle = "Đơn hàng " + code + " đã hủy";
                staffMessage = "Đơn hàng " + code + " (" + customerName + ") đã hủy.";
            }
            case REFUND_PENDING -> {
                customerTitle = "Đơn hàng " + code + " đang chờ hoàn tiền";
                customerMessage = "Yêu cầu hoàn tiền cho đơn " + code + " đã được tạo và đang chờ xử lý.";
                staffTitle = "Đơn " + code + " chờ xác nhận hoàn tiền";
                staffMessage = "Khách hàng " + customerName + " - Đơn " + code + " đang REFUND_PENDING.";
            }
            case REFUNDED -> {
                customerTitle = "Hoàn tiền đơn hàng " + code + " thành công";
                customerMessage = "Khoản hoàn tiền cho đơn " + code + " đã được xác nhận hoàn tất.";
                staffTitle = "Đã hoàn tiền đơn " + code;
                staffMessage = "Đã hoàn tất hoàn tiền cho đơn " + code + " (" + customerName + ").";
            }
            default -> {
                customerTitle = "Cập nhật đơn hàng " + code;
                customerMessage = "Trạng thái mới: " + target.name();
                staffTitle = "Đơn " + code + ": " + target.name();
                staffMessage = customerName + " -> " + target.name();
            }
        }

        NotificationEventPayload payload = NotificationEventPayload.builder()
                .eventKey("ORDER:" + order.getId() + ":STATUS:" + target.name())
                .eventType("ORDER_" + target.name())
                .category("ORDER")
                .referenceType("ORDER")
                .referenceId(order.getId())
                .referenceCode(code)
                .title(customerTitle)
                .message(customerMessage)
                .customerTitle(customerTitle)
                .customerMessage(customerMessage)
                .staffTitle(staffTitle)
                .staffMessage(staffMessage)
                .actionTab("order-hub")
                .actionRoute("/orders")
                .sourceService("order-pos-service")
                .customerId(order.getCustomerId())
                .targetRoles(List.of("ROLE_SALES_STAFF", "ROLE_ADMIN"))
                .build();

        try {
            notificationClient.publishEvent(payload);
        } catch (Exception ex) {
            log.warn("Không thể phát sự kiện thông báo đơn hàng {} sang identity-service: {}",
                    payload.getEventKey(), ex.getMessage());
        }
    }
}
