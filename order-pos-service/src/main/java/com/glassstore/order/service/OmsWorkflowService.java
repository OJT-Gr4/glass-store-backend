package com.glassstore.order.service;

import com.glassstore.order.client.LabClient;
import com.glassstore.order.domain.*;
import com.glassstore.order.dto.OrderDtos;
import com.glassstore.order.entity.Order;
import com.glassstore.order.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service @RequiredArgsConstructor
public class OmsWorkflowService {
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final OrderStatusService statuses;
    private final LabClient lab;

    public void requireCustomerPrescriptions(OrderDtos.CheckoutRequest request) {
        if (!com.glassstore.common.security.CurrentActor.customer()) return;
        var ids=new HashSet<Long>();
        if (request.getPrescriptionId() != null) ids.add(request.getPrescriptionId());
        if (request.getItems() != null) request.getItems().forEach(item -> { if(item.getPrescriptionId()!=null) ids.add(item.getPrescriptionId()); });
        for(Long id:ids) {
            Map<String,Object> prescription;
            try {
                var response=lab.getPrescription(id);prescription=response==null?null:response.getData();
            } catch (feign.FeignException.NotFound error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Prescription does not exist");
            } catch (RuntimeException error) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Cannot verify prescription ownership");
            }
            if (prescription == null || !id.toString().equals(Objects.toString(prescription.get("id"),""))
                    || !com.glassstore.common.security.CurrentActor.id().toString().equals(Objects.toString(prescription.get("customerId"),"")))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Prescription does not belong to this customer");
        }
    }

    @Transactional
    public Order transition(Long id, OrderDtos.StatusUpdateRequest request, String actor) {
        var order = orders.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        OrderStatus target;
        try { target = OrderStatus.parse(request.getStatus()); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()); }
        OrderStateMachine.requireTransition(order.currentStatus(), target);
        if (Set.of(OrderStatus.CANCELLED,OrderStatus.CANCEL_PENDING,OrderStatus.CANCEL_REQUESTED,
                OrderStatus.REFUND_PENDING,OrderStatus.REFUNDED).contains(target))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Use the cancellation/refund workflow so stock and payment are reconciled");
        if (target == OrderStatus.PAID)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirm payment through the payment workflow");
        if (target == OrderStatus.COMPLETED && !payments.existsByOrderIdAndStatus(id, PaymentStatus.SUCCESS))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment has not been confirmed");
        if (Set.of(OrderStatus.SHIPPING,OrderStatus.READY_FOR_PICKUP,OrderStatus.COMPLETED).contains(target)
                && order.getOrderType() != null && order.getOrderType().requiresPrescription()) requireQc(order);
        if (target == OrderStatus.SHIPPING) {
            if (request.getCarrierName() == null || request.getCarrierName().isBlank()
                    || request.getTrackingCode() == null || request.getTrackingCode().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Carrier and tracking code are required");
        }
        if (request.getTrackingCode() != null) order.setTrackingCode(request.getTrackingCode().trim());
        if (request.getCarrierName() != null) order.setCarrierName(request.getCarrierName().trim());
        return statuses.transition(order, target, actor, request.getReason());
    }
    private void requireQc(Order order) {
        Map<String,Object> job;
        try {
            var response = lab.getByOrder(order.getId());
            job = response == null ? null : response.getData();
        } catch (feign.FeignException.NotFound error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No Lab job/QC result exists for this order");
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cannot verify Lab QC; retry before handing over");
        }
        if (job == null || !Objects.toString(order.getId()).equals(Objects.toString(job.get("orderId"),""))
                || !Set.of("QC_PASSED","READY_FOR_DELIVERY","DELIVERED","COMPLETED").contains(Objects.toString(job.get("status"),"")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The order has not passed Lab QC");
    }
}
