package com.glassstore.order.service;
import com.glassstore.order.domain.*;
import com.glassstore.order.entity.*;
import com.glassstore.order.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class OrderCancellationService {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final StockDeductionOutboxRepository outboxes;
    private final PromotionService promotions;
    private final PosDataService data;
    private final ApplicationEventPublisher events;
    private final OrderStatusService statuses;
    public record Request(String reason, boolean refundConfirmed) {}

    @Transactional
    public Order cancel(Long id, Request request, String token) {
        String actor = data.actor(token,false).path("username").asText();
        if(request==null || request.reason()==null || request.reason().isBlank() || request.reason().length()>255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Nhập lý do hủy tối đa 255 ký tự.");
        Order order=orders.lockById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!(order.getChannel() == SalesChannel.POS))throw new ResponseStatusException(HttpStatus.CONFLICT,"Chỉ hủy đơn tại quầy trong luồng này.");
        if((order.getStatus() == OrderStatus.CANCELLED))return order;
        if(!Set.of(OrderStatus.PAID,OrderStatus.CANCEL_PENDING).contains(order.getStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Đơn đã được xử lý tiếp, không thể hủy tại quầy.");
        if(!(order.getStatus() == OrderStatus.CANCEL_PENDING)) {
            statuses.transition(order, OrderStatus.CANCEL_PENDING, actor, request.reason().trim());
            order.setCancelReason(request.reason().trim());order.setCancelledBy(actor);order.setCancelledAt(LocalDateTime.now());
            order.setRefundStatus(request.refundConfirmed()?"CONFIRMED":"PENDING");
            if(request.refundConfirmed()){order.setRefundConfirmedBy(actor);order.setRefundConfirmedAt(LocalDateTime.now());}
            orders.save(order);
            payments.findByOrderId(id).forEach(payment->{payment.setStatus(request.refundConfirmed()?PaymentStatus.REFUNDED:PaymentStatus.REFUND_PENDING);payments.save(payment);});
        }
        StockDeductionOutbox row=outboxes.findByOrderId(id).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Thiếu giao dịch kho gốc, cần quản lý đối chiếu."));
        row=outboxes.lockById(row.getId()).orElseThrow();
        row.setOperation("CANCEL");row.setStatus("PENDING");row.setLastError(null);outboxes.save(row);
        order.setInventorySyncStatus("PENDING");events.publishEvent(new StockDeductionRequestedEvent(row.getId(),order));return order;
    }

    @Transactional
    public void complete(Long id) {
        Order order=orders.lockById(id).orElseThrow();
        if((order.getStatus() == OrderStatus.CANCELLED))return;
        if(!(order.getStatus() == OrderStatus.CANCEL_PENDING))throw new IllegalStateException("Order is not pending cancellation");
        promotions.reverse(order);
        statuses.transition(order, OrderStatus.CANCELLED, order.getCancelledBy(), "Đã hoàn kho và quyền lợi POS");
    }

    @Transactional
    public Order confirmRefund(Long id,String token) {
        String actor;
        try {
            actor = data.actor(token, false).path("username").asText();
        } catch (Exception ignored) {
            actor = com.glassstore.common.security.CurrentActor.name();
        }
        if (actor == null || actor.isBlank()) {
            actor = com.glassstore.common.security.CurrentActor.name();
        }
        Order order=orders.lockById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (order.getStatus() == OrderStatus.REFUNDED && "CONFIRMED".equals(order.getRefundStatus())) {
            return order;
        }
        if(!Set.of(OrderStatus.CANCELLED, OrderStatus.REFUND_PENDING).contains(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Chỉ xác nhận hoàn tiền cho đơn đã hủy hoặc đang chờ hoàn tiền.");
        }
        if(!"CONFIRMED".equals(order.getRefundStatus())) {
            order.setRefundStatus("CONFIRMED");
            order.setRefundConfirmedBy(actor);
            order.setRefundConfirmedAt(LocalDateTime.now());
            if (order.getStatus() == OrderStatus.REFUND_PENDING) {
                order = statuses.transition(order, OrderStatus.REFUNDED, actor, "Đã xác nhận hoàn tiền cho khách hàng");
            } else {
                orders.save(order);
            }
            payments.findByOrderId(id).forEach(payment->{
                if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.REFUND_PENDING) {
                    payment.setStatus(PaymentStatus.REFUNDED);
                    payment.setUpdatedAt(LocalDateTime.now());
                    payments.save(payment);
                }
            });
        }
        return order;
    }
}
