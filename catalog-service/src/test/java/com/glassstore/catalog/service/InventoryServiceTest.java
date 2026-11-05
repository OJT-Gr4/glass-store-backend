package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.StockDeductionRequest;
import com.glassstore.catalog.dto.StockDeductionResponse;
import com.glassstore.catalog.entity.InventoryBalance;
import com.glassstore.catalog.entity.InventoryDeduction;
import com.glassstore.catalog.entity.InventoryMovement;
import com.glassstore.catalog.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryBalanceRepository balanceRepository;
    @Mock
    private InventoryMovementRepository movementRepository;
    @Mock
    private InventoryDeductionRepository deductionRepository;
    @Mock
    private ProductVariantRepository variantRepository;

    @InjectMocks
    private PaidOrderStockDeductionService inventoryService;

    @Test
    void paidOrderDeductsTheResolvedBranchExactlyOnce() {
        StockDeductionRequest request = request();
        when(deductionRepository.findByOrderId(42L)).thenReturn(Optional.empty());
        when(deductionRepository.findByIdempotencyKey("pos-request-42")).thenReturn(Optional.empty());
        when(variantRepository.existsBySku("FRAME-001")).thenReturn(true);
        when(balanceRepository.findBySkuAndLocationCode("FRAME-001", InventoryService.BRANCH_02))
                .thenReturn(Optional.of(InventoryBalance.builder().quantity(8).build()));
        when(balanceRepository.decrementIfAvailable("FRAME-001", InventoryService.BRANCH_02, 3)).thenReturn(1);
        when(variantRepository.decrementStockIfAvailable("FRAME-001", 3)).thenReturn(1);

        StockDeductionResponse response = inventoryService.deduct(request);

        assertEquals(InventoryService.BRANCH_02, response.locationCode());
        assertFalse(response.duplicate());
        verify(balanceRepository).decrementIfAvailable("FRAME-001", InventoryService.BRANCH_02, 3);
        verify(variantRepository).decrementStockIfAvailable("FRAME-001", 3);
        verify(movementRepository).save(argThat(movement ->
                "SALE".equals(movement.getMovementType())
                        && "FRAME-001".equals(movement.getSku())
                        && "TXN-42".equals(movement.getReferenceId())
                        && movement.getQuantity() == 3));
    }

    @Test
    void repeatedPaymentIsIdempotent() {
        StockDeductionRequest request = request();
        InventoryDeduction prior = InventoryDeduction.builder()
                .orderId(42L)
                .idempotencyKey("pos-request-42")
                .transactionCode("TXN-42")
                .locationCode(InventoryService.BRANCH_02)
                .build();
        when(deductionRepository.findByOrderId(42L)).thenReturn(Optional.of(prior));
        when(movementRepository.findAllByReferenceTypeAndReferenceId("PAYMENT", "TXN-42"))
                .thenReturn(java.util.List.of(InventoryMovement.builder()
                        .movementType("SALE")
                        .sku("FRAME-001")
                        .sourceLocationCode(InventoryService.BRANCH_02)
                        .quantity(3)
                        .build()));

        StockDeductionResponse response = inventoryService.deduct(request);

        assertTrue(response.duplicate());
        verifyNoInteractions(balanceRepository, variantRepository);
        verify(movementRepository).findAllByReferenceTypeAndReferenceId("PAYMENT", "TXN-42");
        verify(movementRepository, never()).save(any());
    }

    @Test
    void insufficientBranchStockRejectsTheDeduction() {
        StockDeductionRequest request = request();
        when(deductionRepository.findByOrderId(42L)).thenReturn(Optional.empty());
        when(deductionRepository.findByIdempotencyKey("pos-request-42")).thenReturn(Optional.empty());
        when(variantRepository.existsBySku("FRAME-001")).thenReturn(true);
        when(balanceRepository.findBySkuAndLocationCode("FRAME-001", InventoryService.BRANCH_02))
                .thenReturn(Optional.of(InventoryBalance.builder().quantity(2).build()));
        when(balanceRepository.decrementIfAvailable("FRAME-001", InventoryService.BRANCH_02, 3)).thenReturn(0);
        when(variantRepository.decrementStockIfAvailable("FRAME-001", 3)).thenReturn(1);

        assertThrows(ResponseStatusException.class, () -> inventoryService.deduct(request));
        verify(movementRepository, never()).save(any());
    }

    private StockDeductionRequest request() {
        StockDeductionRequest.Item item = new StockDeductionRequest.Item();
        item.setSku("FRAME-001");
        item.setQuantity(3);

        StockDeductionRequest request = new StockDeductionRequest();
        request.setOrderId(42L);
        request.setIdempotencyKey("pos-request-42");
        request.setTransactionCode("TXN-42");
        request.setBranchName("Chi nhánh 02 - Hai Bà Trưng, Quận 3, TP.HCM");
        request.setItems(java.util.List.of(item));
        return request;
    }
}
