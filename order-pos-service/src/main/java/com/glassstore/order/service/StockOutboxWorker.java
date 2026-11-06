package com.glassstore.order.service;

import com.glassstore.order.client.CatalogInventoryClient;
import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.dto.CatalogStockDeductionRequest;
import com.glassstore.order.entity.Order;
import com.glassstore.order.entity.Payment;
import com.glassstore.order.entity.StockDeductionOutbox;
import com.glassstore.order.repository.OrderRepository;
import com.glassstore.order.repository.PaymentRepository;
import com.glassstore.order.repository.StockDeductionOutboxRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class StockOutboxWorker {

    private static final int MAX_ATTEMPTS = 10;

    private final StockDeductionOutboxRepository outboxes;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final CatalogInventoryClient catalog;
    private final OrderCancellationService cancellations;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String process(Long id) {
        Long orderId = outboxes.findOrderIdById(id).orElseThrow();
        Order order = orders.lockById(orderId).orElseThrow();
        StockDeductionOutbox row = outboxes.lockById(id).orElseThrow();
        if (!"PENDING".equals(row.getStatus())) {
            return row.getStatus();
        }

        boolean cancel = "CANCEL".equals(row.getOperation());
        if (!cancel && order.getStatus() != OrderStatus.PAID) {
            row.setStatus("FAILED");
            row.setLastError("Đơn không còn trạng thái đã thanh toán.");
            outboxes.save(row);
            return row.getStatus();
        }

        Payment payment = payments.findByOrderId(order.getId()).stream()
                .filter(candidate -> row.getIdempotencyKey().equals(candidate.getIdempotencyKey()))
                .findFirst()
                .orElseThrow();
        CatalogStockDeductionRequest request = new CatalogStockDeductionRequest(
                order.getId(),
                row.getIdempotencyKey(),
                payment.getTransactionCode(),
                order.getBranchCode() != null ? order.getBranchCode() : order.getBranchName(),
                order.getItems().stream()
                        .map(item -> new CatalogStockDeductionRequest.Item(item.getSkuId(), item.getQuantity()))
                        .toList());

        markAttempt(row);
        try {
            if (cancel) {
                catalog.cancelSale(request);
                cancellations.complete(order.getId());
            } else {
                var acknowledgement = catalog.deductPaidOrder(request);
                if (acknowledgement == null || !order.getId().equals(acknowledgement.orderId())) {
                    throw new IllegalStateException("Kho chưa xác nhận đúng mã đơn đã xuất.");
                }
            }
            row.setStatus("SYNCED");
            row.setProcessedAt(LocalDateTime.now());
            row.setLastError(null);
        } catch (FeignException error) {
            recordFailure(row, error.getMessage(), isRetryable(error.status()));
        } catch (RuntimeException error) {
            recordFailure(row, error.getMessage(), true);
        }
        outboxes.save(row);
        return row.getStatus();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String recordProcessingFailure(Long id, RuntimeException error) {
        StockDeductionOutbox row = outboxes.lockById(id).orElseThrow();
        if (!"PENDING".equals(row.getStatus())) {
            return row.getStatus();
        }
        markAttempt(row);
        recordFailure(row, error.getMessage(), true);
        outboxes.save(row);
        return row.getStatus();
    }

    private void markAttempt(StockDeductionOutbox row) {
        row.setAttempts((row.getAttempts() == null ? 0 : row.getAttempts()) + 1);
        row.setLastAttemptAt(LocalDateTime.now());
    }

    private void recordFailure(StockDeductionOutbox row, String message, boolean retryable) {
        String error = message == null ? "Không nhận được xác nhận từ dịch vụ kho." : message;
        row.setLastError(error.substring(0, Math.min(1000, error.length())));
        if (!retryable) {
            row.setStatus("FAILED");
        } else if (row.getAttempts() >= MAX_ATTEMPTS) {
            row.setStatus("RECONCILIATION_REQUIRED");
        } else {
            row.setStatus("PENDING");
        }
    }

    private boolean isRetryable(int status) {
        return status < 0 || status == 408 || status == 425 || status == 429 || status >= 500;
    }
}
