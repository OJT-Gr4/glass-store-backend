package com.glassstore.lab.service;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.lab.client.WarrantyOrderClient;
import com.glassstore.lab.dto.RepairServiceCreateRequest;
import com.glassstore.lab.dto.RepairServiceStatusRequest;
import com.glassstore.lab.dto.WarrantyOrderItemDTO;
import com.glassstore.lab.entity.RepairService;
import com.glassstore.lab.repository.RepairServiceRepository;
import com.glassstore.lab.repository.RepairServiceStatusHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RepairServiceWorkflowTest {

    private RepairServiceRepository repairRepository;
    private RepairServiceStatusHistoryRepository historyRepository;
    private WarrantyOrderClient orderClient;
    private RepairServiceWorkflow workflow;

    @BeforeEach
    void setUp() {
        repairRepository = mock(RepairServiceRepository.class);
        historyRepository = mock(RepairServiceStatusHistoryRepository.class);
        orderClient = mock(WarrantyOrderClient.class);
        workflow = new RepairServiceWorkflow(repairRepository, historyRepository, orderClient);
        when(repairRepository.save(any(RepairService.class))).thenAnswer(invocation -> {
            RepairService ticket = invocation.getArgument(0);
            ticket.setId(42L);
            return ticket;
        });
        when(historyRepository.findByRepairServiceIdOrderByChangedAtAsc(42L)).thenReturn(List.of());
        when(orderClient.getPurchaseItem(10L, 20L)).thenReturn(new WarrantyOrderItemDTO(
                10L, 20L, "ORD-001", 99L, "Customer", "0901234567",
                "SKU-001", "Frame A", LocalDateTime.now().minusDays(2), "PAID", null, null, 0, null));
    }

    @Test
    void createsReceivedTicketAndInitialHistory() {
        var result = workflow.create(validRequest());

        assertThat(result.status()).isEqualTo("RECEIVED");
        assertThat(result.requestedServices()).containsExactly("FRAME_ADJUSTMENT", "ULTRASONIC_CLEANING");
        assertThat(result.receivedAt()).isNotNull();
        verify(historyRepository).save(argThat(history -> "RECEIVED".equals(history.getNewStatus())));
    }

    @Test
    void allowsForwardStatusTransition() {
        RepairService ticket = RepairService.builder().id(42L).status("RECEIVED").build();
        when(repairRepository.findById(42L)).thenReturn(Optional.of(ticket));

        var result = workflow.updateStatus(42L, new RepairServiceStatusRequest("IN_PROGRESS", "Staff A", null));

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        verify(historyRepository).save(argThat(history ->
                "RECEIVED".equals(history.getPreviousStatus()) && "IN_PROGRESS".equals(history.getNewStatus())));
    }

    @Test
    void rejectsBackwardStatusTransition() {
        RepairService ticket = RepairService.builder().id(42L).status("READY_FOR_PICKUP").build();
        when(repairRepository.findById(42L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> workflow.updateStatus(
                42L, new RepairServiceStatusRequest("RECEIVED", "Staff A", null)))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.REPAIR_SERVICE_INVALID_TRANSITION);
    }

    private RepairServiceCreateRequest validRequest() {
        return new RepairServiceCreateRequest(
                "Customer", "0901234567", "Frame A", "Eyeglass frame", null,
                "Loose hinge", "Scratched lens", "Temple is bent", "No visible crack",
                List.of("FRAME_ADJUSTMENT", "ULTRASONIC_CLEANING"), "Tech A", "Staff A",
                LocalDateTime.now().plusDays(1), null, "Receipt note",
                10L, 20L, 99L);
    }
}
