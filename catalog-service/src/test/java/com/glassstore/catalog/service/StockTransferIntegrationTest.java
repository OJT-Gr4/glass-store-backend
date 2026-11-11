package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.CreateStockTransferRequest;
import com.glassstore.catalog.dto.StockDeductionRequest;
import com.glassstore.catalog.dto.StockDeductionResponse;
import com.glassstore.catalog.dto.StockTransferDTO;
import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stock-transfer-test;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureMockMvc
class StockTransferIntegrationTest {

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private InventoryReconciliationService reconciliationService;
    @Autowired
    private InventoryLocationRepository locationRepository;
    @Autowired
    private InventoryBalanceRepository balanceRepository;
    @Autowired
    private InventoryMovementRepository movementRepository;
    @Autowired
    private InventoryDeductionRepository deductionRepository;
    @Autowired
    private ProductVariantRepository variantRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private StockTransferRepository transferRepository;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void dispatchesAndReceivesBothDirectionsWithoutChangingTotalStock() {
        TestStock stock = createStock(20, 5);
        StockTransferDTO pending = inventoryService.createTransfer(
                request(stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 3), "lab.operator");

        assertEquals("PENDING", pending.getStatus());
        assertEquals("lab.operator", pending.getCreatedBy());
        assertBalances(stock.sku(), 20, 5);
        assertEquals(0, movementsFor(pending.getTransferCode()).size());

        assertStatus(HttpStatus.CONFLICT,
                () -> inventoryService.receiveTransfer(pending.getId(), "admin.operator"));

        StockTransferDTO dispatched = inventoryService.dispatchTransfer(pending.getId(), "lab.operator");
        assertEquals("IN_TRANSIT", dispatched.getStatus());
        assertEquals("lab.operator", dispatched.getDispatchedBy());
        assertBalances(stock.sku(), 17, 5);
        assertEquals(22, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        assertEquals(1, movementsFor(pending.getTransferCode()).size());
        assertStatus(HttpStatus.CONFLICT,
                () -> inventoryService.dispatchTransfer(pending.getId(), "lab.operator"));

        StockTransferDTO received = inventoryService.receiveTransfer(pending.getId(), "admin.operator");
        assertEquals("RECEIVED", received.getStatus());
        assertEquals("admin.operator", received.getReceivedBy());
        assertBalances(stock.sku(), 17, 8);
        assertEquals(25, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        assertEquals(2, movementsFor(pending.getTransferCode()).size());
        assertThrows(ResponseStatusException.class,
                () -> inventoryService.receiveTransfer(pending.getId(), "admin.operator"));
        assertBalances(stock.sku(), 17, 8);
        assertEquals(25, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        assertEquals(2, movementsFor(pending.getTransferCode()).size());

        StockTransferDTO reversePending = inventoryService.createTransfer(
                request(stock.sku(), InventoryService.BRANCH_01, InventoryService.LAB, 2), "admin.operator");
        assertBalances(stock.sku(), 17, 8);
        inventoryService.dispatchTransfer(reversePending.getId(), "admin.operator");
        assertBalances(stock.sku(), 17, 6);
        assertEquals(23, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        inventoryService.receiveTransfer(reversePending.getId(), "lab.operator");
        assertBalances(stock.sku(), 19, 6);
        assertEquals(25, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        assertTrue(reconciliationService.reconcile().getBalanceVariances().stream()
                .noneMatch(variance -> variance.getSku().equals(stock.sku())));
    }

    @Test
    void rejectsInvalidDraftsAndInsufficientSourceStock() {
        TestStock stock = createStock(4, 2);

        assertStatus(HttpStatus.BAD_REQUEST, () -> inventoryService.createTransfer(
                request(stock.sku(), InventoryService.LAB, InventoryService.LAB, 1), "admin"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> inventoryService.createTransfer(
                request(stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 0), "admin"));
        assertStatus(HttpStatus.CONFLICT, () -> inventoryService.createTransfer(
                request(stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 5), "admin"));
        assertStatus(HttpStatus.NOT_FOUND, () -> inventoryService.createTransfer(
                request("UNKNOWN-" + stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 1), "admin"));
        CreateStockTransferRequest duplicate = request(
                stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 1);
        duplicate.setItems(List.of(item(stock.sku(), 1), item(stock.sku(), 1)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> inventoryService.createTransfer(duplicate, "admin"));
        CreateStockTransferRequest empty = request(stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 1);
        empty.setItems(List.of());
        assertStatus(HttpStatus.BAD_REQUEST, () -> inventoryService.createTransfer(empty, "admin"));
        assertBalances(stock.sku(), 4, 2);
    }

    @Test
    void transferRoutesSeparateDispatchAndReceiptAndRequireWarehouseRoles() throws Exception {
        TestStock stock = createStock(8, 0);
        StockTransferDTO transfer = inventoryService.createTransfer(
                request(stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 2), "lab.operator");

        mockMvc.perform(patch("/api/inventory/transfers/{id}/dispatch", transfer.getId())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_STAFF"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/inventory/transfers/{id}/dispatch", transfer.getId())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status")
                        .value("IN_TRANSIT"));
        mockMvc.perform(patch("/api/inventory/transfers/{id}/receive", transfer.getId())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LAB"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status")
                        .value("RECEIVED"));
    }

    @Test
    void inventoryResponseIncludesSellableQuantityAfterQuarantine() {
        TestStock stock = createStock(10, 5);
        InventoryBalance labBalance = balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.LAB).orElseThrow();
        labBalance.setQuarantinedQuantity(3);
        balanceRepository.save(labBalance);

        var row = inventoryService.getInventory().stream()
                .filter(item -> item.getSku().equals(stock.sku()))
                .findFirst()
                .orElseThrow();
        assertEquals(10, row.getQuantities().get(InventoryService.LAB));
        assertEquals(7, row.getSellableQuantities().get(InventoryService.LAB));
    }

    @Test
    void reconciliationDetectsBalanceAndUnexpectedTransferMovementsWithoutRepairingStock() {
        TestStock stock = createStock(8, 4);
        StockTransferDTO transfer = inventoryService.createTransfer(
                request(stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 2), "lab.operator");
        inventoryService.dispatchTransfer(transfer.getId(), "lab.operator");

        var cleanReport = reconciliationService.reconcile();
        assertFalse(cleanReport.getBalanceVariances().stream()
                .anyMatch(variance -> variance.getSku().equals(stock.sku())));
        assertFalse(cleanReport.getTransferVariances().stream()
                .anyMatch(variance -> variance.getTransferCode().equals(transfer.getTransferCode())));
        assertFalse(cleanReport.isCompleteMovementLedger());

        InventoryBalance branchBalance = balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.BRANCH_01).orElseThrow();
        branchBalance.setQuantity(branchBalance.getQuantity() - 1);
        balanceRepository.save(branchBalance);
        movementRepository.save(InventoryMovement.builder()
                .movementType("TRANSFER_OUT")
                .sku("UNLISTED-" + stock.sku())
                .sourceLocationCode(InventoryService.LAB)
                .destinationLocationCode(InventoryService.BRANCH_01)
                .referenceType("STOCK_TRANSFER")
                .referenceId(transfer.getTransferCode())
                .quantity(1)
                .performedBy("test")
                .build());

        var report = reconciliationService.reconcile();
        assertTrue(report.getBalanceVariances().stream()
                .anyMatch(variance -> variance.getSku().equals(stock.sku()) && variance.getDifference() == 1));
        assertTrue(report.getTransferVariances().stream()
                .anyMatch(variance -> variance.getSku().equals("UNLISTED-" + stock.sku())));
        assertEquals(6, balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.LAB).orElseThrow().getQuantity());
        assertEquals(3, balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.BRANCH_01).orElseThrow().getQuantity());
        assertEquals(10, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
    }

    @Test
    void insufficientStockAtDispatchRollsBackEverySkuAndKeepsTransferPending() {
        TestStock first = createStock(10, 0);
        TestStock second = createStock(10, 0);
        CreateStockTransferRequest request = request(first.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 2);
        request.setItems(List.of(item(first.sku(), 2), item(second.sku(), 2)));
        StockTransferDTO transfer = inventoryService.createTransfer(request, "lab.operator");

        InventoryBalance secondSource = balanceRepository.findBySkuAndLocationCode(
                second.sku(), InventoryService.LAB).orElseThrow();
        secondSource.setQuarantinedQuantity(10);
        balanceRepository.save(secondSource);

        assertStatus(HttpStatus.CONFLICT,
                () -> inventoryService.dispatchTransfer(transfer.getId(), "admin.operator"));
        assertBalances(first.sku(), 10, 0);
        assertBalances(second.sku(), 10, 0);
        assertEquals(10, variantRepository.findBySku(first.sku()).orElseThrow().getStockQuantity());
        assertEquals(10, variantRepository.findBySku(second.sku()).orElseThrow().getStockQuantity());
        assertTrue(movementsFor(transfer.getTransferCode()).isEmpty());
        assertEquals("PENDING", transferRepository.findById(transfer.getId()).orElseThrow().getStatus().name());

        secondSource.setQuarantinedQuantity(0);
        balanceRepository.save(secondSource);
        assertEquals("IN_TRANSIT", inventoryService.dispatchTransfer(
                transfer.getId(), "admin.operator").getStatus());
        assertBalances(first.sku(), 8, 0);
        assertBalances(second.sku(), 8, 0);
        assertEquals(8, variantRepository.findBySku(first.sku()).orElseThrow().getStockQuantity());
        assertEquals(8, variantRepository.findBySku(second.sku()).orElseThrow().getStockQuantity());
        assertEquals(2, movementsFor(transfer.getTransferCode()).size());
        inventoryService.receiveTransfer(transfer.getId(), "branch.operator");
        assertBalances(first.sku(), 8, 2);
        assertBalances(second.sku(), 8, 2);
        assertEquals(10, variantRepository.findBySku(first.sku()).orElseThrow().getStockQuantity());
        assertEquals(10, variantRepository.findBySku(second.sku()).orElseThrow().getStockQuantity());
        assertEquals(4, movementsFor(transfer.getTransferCode()).size());
    }

    @Test
    void concurrentReceiptRequestsUpdateStockOnlyOnce() throws Exception {
        TestStock stock = createStock(10, 0);
        StockTransferDTO transfer = inventoryService.createTransfer(
                request(stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 4), "lab.operator");
        inventoryService.dispatchTransfer(transfer.getId(), "lab.operator");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> receiveAfter(start, transfer.getId()));
            Future<Boolean> second = executor.submit(() -> receiveAfter(start, transfer.getId()));
            start.countDown();
            assertEquals(1, (int) List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        } finally {
            executor.shutdownNow();
        }

        assertBalances(stock.sku(), 6, 4);
        assertEquals(10, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        assertEquals(2, movementsFor(transfer.getTransferCode()).size());
    }

    @Test
    void concurrentDispatchRequestsDeductAndRecordSourceOnlyOnce() throws Exception {
        TestStock stock = createStock(10, 0);
        StockTransferDTO transfer = inventoryService.createTransfer(
                request(stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 4), "lab.operator");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> dispatchAfter(start, transfer.getId()));
            Future<Boolean> second = executor.submit(() -> dispatchAfter(start, transfer.getId()));
            start.countDown();
            assertEquals(1, (int) List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        } finally {
            executor.shutdownNow();
        }

        assertBalances(stock.sku(), 6, 0);
        assertEquals(6, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        assertEquals(1, movementsFor(transfer.getTransferCode()).size());
    }

    @Test
    void legacyInTransitReceiptCreditsDestinationWithoutDeductingSourceTwice() {
        TestStock stock = createStock(10, 2);
        StockTransferDTO draft = inventoryService.createTransfer(
                request(stock.sku(), InventoryService.LAB, InventoryService.BRANCH_01, 3), "lab.operator");
        StockTransfer legacyTransfer = transferRepository.findById(draft.getId()).orElseThrow();
        legacyTransfer.setStatus(StockTransferStatus.IN_TRANSIT);
        legacyTransfer.setDispatchedAt(LocalDateTime.now());
        transferRepository.save(legacyTransfer);

        balanceRepository.findBySkuAndLocationCode(stock.sku(), InventoryService.LAB)
                .ifPresent(balance -> {
                    balance.setQuantity(7);
                    balanceRepository.save(balance);
                });
        ProductVariant variant = variantRepository.findBySku(stock.sku()).orElseThrow();
        variant.setStockQuantity(9);
        variantRepository.save(variant);

        inventoryService.receiveTransfer(draft.getId(), "lab.operator");
        assertBalances(stock.sku(), 7, 5);
        assertEquals(12, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
    }

    @Test
    void inventoryEndpointsRequireExistingPortalRoles() throws Exception {
        mockMvc.perform(get("/api/inventory"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/inventory")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_POS"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LAB"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inventory")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LAB_TECH"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inventory")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_STAFF"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inventory/reconciliation")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LAB"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/reconciliation")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/inventory/transfers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SALES_STAFF"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void movementEndpointBindsOptionalFiltersAndPagination() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        mockMvc.perform(get("/api/inventory/movements").with(admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/inventory/movements")
                        .param("sku", "UNKNOWN-SKU").param("locationCode", "LAB")
                        .param("referenceId", "UNKNOWN-TRANSFER").param("page", "0").param("size", "10")
                        .with(admin))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.size").value(10));
        mockMvc.perform(get("/api/inventory/movements").param("page", "-1").with(admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void paidOrderDeductionUsesItsBranchAndReplaysWithoutWritingDuplicateMovement() {
        TestStock stock = createStock(0, 3);
        ProductVariant variant = variantRepository.findBySku(stock.sku()).orElseThrow();
        variant.setStockQuantity(10);
        variantRepository.save(variant);
        InventoryLocation branch02 = locationRepository.findByCodeAndActiveTrue(InventoryService.BRANCH_02)
                .orElseThrow();
        balanceRepository.save(InventoryBalance.builder()
                .variant(variant)
                .location(branch02)
                .quantity(7)
                .build());

        StockDeductionRequest request = saleRequest(501L, "sale-key-501", "TXN-501",
                "BRANCH_02", stock.sku(), 2);
        StockDeductionResponse first = inventoryService.deductPaidOrder(request);

        assertFalse(first.duplicate());
        for (int retry = 0; retry < 3; retry++) {
            assertTrue(inventoryService.deductPaidOrder(request).duplicate());
        }
        assertEquals(3, balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.BRANCH_01).orElseThrow().getQuantity());
        assertEquals(5, balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.BRANCH_02).orElseThrow().getQuantity());
        assertEquals(8, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        List<InventoryMovement> movements = movementRepository
                .findAllByReferenceTypeAndReferenceId("PAYMENT", "TXN-501");
        assertEquals(1, movements.size());
        assertEquals(InventoryService.BRANCH_02, movements.get(0).getSourceLocationCode());
        assertEquals("ORDER_SERVICE", movements.get(0).getPerformedBy());
        assertEquals(2, movements.get(0).getQuantity());
    }

    @Test
    void aggregateStockCannotAuthorizeASaleFromAnEmptyBranch() {
        TestStock stock = createStock(0, 3);
        ProductVariant variant = variantRepository.findBySku(stock.sku()).orElseThrow();
        InventoryLocation branch02 = locationRepository.findByCodeAndActiveTrue(InventoryService.BRANCH_02)
                .orElseThrow();
        balanceRepository.save(InventoryBalance.builder()
                .variant(variant)
                .location(branch02)
                .quantity(0)
                .build());

        assertStatus(HttpStatus.CONFLICT, () -> inventoryService.deductPaidOrder(
                saleRequest(507L, "sale-key-507", "TXN-507", "BRANCH_02", stock.sku(), 1)));

        assertEquals(3, balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.BRANCH_01).orElseThrow().getQuantity());
        assertEquals(0, balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.BRANCH_02).orElseThrow().getQuantity());
        assertEquals(3, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        assertTrue(movementRepository.findAllByReferenceTypeAndReferenceId("PAYMENT", "TXN-507").isEmpty());
    }

    @Test
    void insufficientSkuRollsBackAllDeductionsAndMovementHistory() {
        TestStock first = createStock(0, 5);
        TestStock second = createStock(0, 1);
        StockDeductionRequest request = saleRequest(502L, "sale-key-502", "TXN-502",
                "BRANCH_01", first.sku(), 2);
        request.setItems(List.of(saleItem(first.sku(), 2), saleItem(second.sku(), 2)));

        assertStatus(HttpStatus.CONFLICT, () -> inventoryService.deductPaidOrder(request));

        assertEquals(5, balanceRepository.findBySkuAndLocationCode(
                first.sku(), InventoryService.BRANCH_01).orElseThrow().getQuantity());
        assertEquals(1, balanceRepository.findBySkuAndLocationCode(
                second.sku(), InventoryService.BRANCH_01).orElseThrow().getQuantity());
        assertEquals(5, variantRepository.findBySku(first.sku()).orElseThrow().getStockQuantity());
        assertEquals(1, variantRepository.findBySku(second.sku()).orElseThrow().getStockQuantity());
        assertTrue(movementRepository.findAllByReferenceTypeAndReferenceId("PAYMENT", "TXN-502").isEmpty());
        assertTrue(deductionRepository.findUnlockedByOrderId(502L).isEmpty());
    }

    @Test
    void concurrentSalesForTheLastBranchUnitAllowOnlyOneDeduction() throws Exception {
        TestStock stock = createStock(0, 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> deductAfter(start,
                    saleRequest(503L, "sale-key-503", "TXN-503", "BRANCH_01", stock.sku(), 1)));
            Future<Boolean> second = executor.submit(() -> deductAfter(start,
                    saleRequest(504L, "sale-key-504", "TXN-504", "BRANCH_01", stock.sku(), 1)));
            start.countDown();
            assertEquals(1, (int) List.of(first.get(), second.get())
                    .stream().filter(Boolean::booleanValue).count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.BRANCH_01).orElseThrow().getQuantity());
        assertEquals(0, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        assertEquals(1, movementRepository.findAllByReferenceTypeAndReferenceId("PAYMENT", "TXN-503").size()
                + movementRepository.findAllByReferenceTypeAndReferenceId("PAYMENT", "TXN-504").size());
    }

    @Test
    void concurrentReplayOfTheSameSaleReturnsDuplicateWithoutDeductingTwice() throws Exception {
        TestStock stock = createStock(0, 4);
        StockDeductionRequest request = saleRequest(505L, "sale-key-505", "TXN-505",
                "BRANCH_01", stock.sku(), 2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StockDeductionResponse> first = executor.submit(() -> deductReplayAfter(start, request));
            Future<StockDeductionResponse> second = executor.submit(() -> deductReplayAfter(start, request));
            start.countDown();
            List<StockDeductionResponse> responses = List.of(first.get(), second.get());
            assertEquals(1, (int) responses.stream().filter(StockDeductionResponse::duplicate).count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.BRANCH_01).orElseThrow().getQuantity());
        assertEquals(2, variantRepository.findBySku(stock.sku()).orElseThrow().getStockQuantity());
        assertEquals(1, movementRepository.findAllByReferenceTypeAndReferenceId("PAYMENT", "TXN-505").size());
    }

    @Test
    void reusingAnIdempotencyKeyWithDifferentItemsIsRejected() {
        TestStock stock = createStock(0, 5);
        StockDeductionRequest request = saleRequest(506L, "sale-key-506", "TXN-506",
                "BRANCH_01", stock.sku(), 1);
        inventoryService.deductPaidOrder(request);
        request.setItems(List.of(saleItem(stock.sku(), 2)));

        assertStatus(HttpStatus.CONFLICT, () -> inventoryService.deductPaidOrder(request));
        assertEquals(4, balanceRepository.findBySkuAndLocationCode(
                stock.sku(), InventoryService.BRANCH_01).orElseThrow().getQuantity());
        assertEquals(1, movementRepository.findAllByReferenceTypeAndReferenceId("PAYMENT", "TXN-506").size());
    }

    private boolean receiveAfter(CountDownLatch start, Long transferId) throws Exception {
        start.await();
        try {
            inventoryService.receiveTransfer(transferId, "admin.operator");
            return true;
        } catch (ResponseStatusException exception) {
            assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
            return false;
        }
    }

    private boolean dispatchAfter(CountDownLatch start, Long transferId) throws Exception {
        start.await();
        try {
            inventoryService.dispatchTransfer(transferId, "admin.operator");
            return true;
        } catch (ResponseStatusException exception) {
            assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
            return false;
        }
    }

    private boolean deductAfter(CountDownLatch start, StockDeductionRequest request) throws Exception {
        start.await();
        try {
            inventoryService.deductPaidOrder(request);
            return true;
        } catch (ResponseStatusException exception) {
            assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
            return false;
        }
    }

    private StockDeductionResponse deductReplayAfter(CountDownLatch start, StockDeductionRequest request)
            throws Exception {
        start.await();
        return inventoryService.deductPaidOrder(request);
    }

    private StockDeductionRequest saleRequest(
            Long orderId, String idempotencyKey, String transactionCode, String branchName,
            String sku, int quantity) {
        StockDeductionRequest request = new StockDeductionRequest();
        request.setOrderId(orderId);
        request.setIdempotencyKey(idempotencyKey);
        request.setTransactionCode(transactionCode);
        request.setBranchName(branchName);
        request.setItems(List.of(saleItem(sku, quantity)));
        return request;
    }

    private StockDeductionRequest.Item saleItem(String sku, int quantity) {
        StockDeductionRequest.Item item = new StockDeductionRequest.Item();
        item.setSku(sku);
        item.setQuantity(quantity);
        return item;
    }

    private TestStock createStock(int labQuantity, int branchQuantity) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Brand brand = brandRepository.save(Brand.builder().name("Transfer-" + suffix).build());
        Category category = categoryRepository.save(Category.builder()
                .name("Transfer-" + suffix)
                .slug("transfer-" + suffix)
                .build());
        Product product = productRepository.save(Product.builder()
                .name("Transfer " + suffix)
                .slug("transfer-product-" + suffix)
                .category(category)
                .brand(brand)
                .productType(ProductType.FRAME)
                .build());
        String sku = "TR-" + suffix.substring(0, 20);
        ProductVariant variant = variantRepository.save(ProductVariant.builder()
                .product(product)
                .sku(sku)
                .barcode("BAR-" + suffix)
                .name("Transfer item")
                .price(BigDecimal.ONE)
                .costPrice(BigDecimal.ONE)
                .stockQuantity(labQuantity + branchQuantity)
                .build());
        InventoryLocation lab = locationRepository.findByCodeAndActiveTrue(InventoryService.LAB).orElseThrow();
        InventoryLocation branch = locationRepository.findByCodeAndActiveTrue(InventoryService.BRANCH_01).orElseThrow();
        balanceRepository.saveAll(List.of(
                InventoryBalance.builder().variant(variant).location(lab).quantity(labQuantity).build(),
                InventoryBalance.builder().variant(variant).location(branch).quantity(branchQuantity).build()));
        return new TestStock(sku);
    }

    private CreateStockTransferRequest request(
            String sku, String sourceCode, String destinationCode, int quantity) {
        CreateStockTransferRequest request = new CreateStockTransferRequest();
        request.setSourceLocationCode(sourceCode);
        request.setDestinationLocationCode(destinationCode);
        request.setReason("integration test");
        request.setItems(List.of(item(sku, quantity)));
        return request;
    }

    private CreateStockTransferRequest.Item item(String sku, int quantity) {
        CreateStockTransferRequest.Item item = new CreateStockTransferRequest.Item();
        item.setSku(sku);
        item.setQuantity(quantity);
        return item;
    }

    private void assertBalances(String sku, int expectedLab, int expectedBranch) {
        assertEquals(expectedLab, balanceRepository.findBySkuAndLocationCode(sku, InventoryService.LAB)
                .orElseThrow().getQuantity());
        assertEquals(expectedBranch, balanceRepository.findBySkuAndLocationCode(sku, InventoryService.BRANCH_01)
                .orElseThrow().getQuantity());
    }

    private List<InventoryMovement> movementsFor(String transferCode) {
        return movementRepository.findByFilters(null, null, transferCode,
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent();
    }

    private void assertStatus(HttpStatus expected, Runnable action) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, exception.getStatusCode());
    }

    private record TestStock(String sku) {
    }
}
