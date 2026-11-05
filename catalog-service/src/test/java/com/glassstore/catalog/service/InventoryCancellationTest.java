package com.glassstore.catalog.service;
import com.glassstore.catalog.dto.StockDeductionRequest;
import com.glassstore.catalog.entity.InventoryDeduction;
import com.glassstore.catalog.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryCancellationTest {
 @Mock InventoryLocationRepository locationRepository;
 @Mock InventoryBalanceRepository balanceRepository;
 @Mock InventoryMovementRepository movementRepository;
 @Mock InventoryDeductionRepository deductionRepository;
 @Mock StockTransferRepository transferRepository;
 @Mock ProductVariantRepository variantRepository;
 private InventoryService service;
 @BeforeEach void setUp() {
  PaidOrderStockDeductionService paidOrderStockDeductionService = new PaidOrderStockDeductionService(
    balanceRepository, deductionRepository, movementRepository, variantRepository);
  service = new InventoryService(locationRepository, balanceRepository, movementRepository,
    deductionRepository, transferRepository, variantRepository, paidOrderStockDeductionService);
 }
 private StockDeductionRequest request(){StockDeductionRequest r=new StockDeductionRequest();r.setOrderId(42L);r.setIdempotencyKey("key");r.setTransactionCode("TXN");r.setBranchName("BRANCH_01");StockDeductionRequest.Item item=new StockDeductionRequest.Item();item.setSku("SKU");item.setQuantity(2);r.setItems(List.of(item));return r;}
 @Test void cancelRestoresOnceToOriginalLocation(){InventoryDeduction d=InventoryDeduction.builder().orderId(42L).idempotencyKey("key").transactionCode("TXN").locationCode("BRANCH_02").build();when(deductionRepository.findByOrderId(42L)).thenReturn(Optional.of(d));when(balanceRepository.increment("SKU","BRANCH_02",2)).thenReturn(1);when(variantRepository.incrementStock("SKU",2)).thenReturn(1);service.cancelSale(request());service.cancelSale(request());verify(balanceRepository,times(1)).increment("SKU","BRANCH_02",2);verify(variantRepository,times(1)).incrementStock("SKU",2);assertTrue(d.getCancelled());}
 @Test void cancellationBeforeDeductionCreatesTombstoneWithoutAddingStock(){when(deductionRepository.findByOrderId(42L)).thenReturn(Optional.empty());ArgumentCaptor<InventoryDeduction> saved=ArgumentCaptor.forClass(InventoryDeduction.class);service.cancelSale(request());verify(deductionRepository).saveAndFlush(saved.capture());assertTrue(saved.getValue().getCancelled());verifyNoInteractions(balanceRepository,variantRepository,movementRepository);when(deductionRepository.findByOrderId(42L)).thenReturn(Optional.of(saved.getValue()));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.deductPaidOrder(request()));verifyNoInteractions(balanceRepository,variantRepository,movementRepository);}
}
