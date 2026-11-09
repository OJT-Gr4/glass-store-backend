package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.StockVoucherDtos;
import com.glassstore.catalog.dto.SupplierDtos;
import com.glassstore.catalog.dto.CreateStockTransferRequest;
import com.glassstore.catalog.dto.StockCheckRequest;
import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stock-voucher-test;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class StockVoucherIntegrationTest {

    @Autowired
    private StockVoucherService stockVoucherService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private SupplierService supplierService;
    @Autowired
    private InventoryLocationRepository locationRepository;
    @Autowired
    private InventoryBalanceRepository balanceRepository;
    @Autowired
    private InventoryMovementRepository movementRepository;
    @Autowired
    private ProductVariantRepository variantRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private BrandRepository brandRepository;

    @Test
    void createsPendingReceiptWithoutChangingStockAndReplaysSameIdempotencyKey() {
        TestStock stock = createStock(5);
        StockVoucherDtos.CreateRequest request = request(
                StockVoucherType.RECEIPT, stock.sku(), 4, 1, null);

        StockVoucherDtos.Response created = stockVoucherService.create(request, "receipt-key-1", "lab.operator");
        StockVoucherDtos.Response replay = stockVoucherService.create(request, "receipt-key-1", "lab.operator");

        assertEquals(created.id(), replay.id());
        assertEquals("PENDING", created.status());
        assertEquals("lab.operator", created.createdBy());
        assertBalances(stock.sku(), 5, 0, 5, 0);
        assertTrue(movementRepository.findAllByReferenceTypeAndReferenceId(
                "STOCK_VOUCHER", created.voucherCode()).isEmpty());
    }

    @Test
    void approvalAddsSellableAndQuarantinedReceiptUnitsAndCannotRunTwice() {
        TestStock stock = createStock(5);
        StockVoucherDtos.Response pending = stockVoucherService.create(
                request(StockVoucherType.RECEIPT, stock.sku(), 4, 1, null), "receipt-key-2", "lab.operator");

        StockVoucherDtos.Response approved = stockVoucherService.approve(pending.id(), "admin.operator");

        assertEquals("APPROVED", approved.status());
        assertEquals("admin.operator", approved.decidedBy());
        assertBalances(stock.sku(), 9, 1, 9, 1);
        List<InventoryMovement> movements = movementRepository.findAllByReferenceTypeAndReferenceId(
                "STOCK_VOUCHER", pending.voucherCode());
        assertEquals(2, movements.size());
        assertEquals(4, movements.stream().mapToInt(InventoryMovement::getQuantity).sum());
        assertEquals(1, countMovementType(movements, "DEFECT_IN"));
        assertEquals(1, countMovementType(movements, "STOCK_IN"));

        assertStatus(HttpStatus.CONFLICT, () -> stockVoucherService.approve(pending.id(), "admin.operator"));
        assertBalances(stock.sku(), 9, 1, 9, 1);
        assertEquals(2, movementRepository.findAllByReferenceTypeAndReferenceId(
                "STOCK_VOUCHER", pending.voucherCode()).size());
    }

    @Test
    void issueCannotConsumeQuarantinedUnitsAndValidIssueUpdatesBothStockViews() {
        TestStock stock = createStock(5);
        StockVoucherDtos.Response quarantine = stockVoucherService.create(
                request(StockVoucherType.QUARANTINE, stock.sku(), 2, 0, null), "defect-key-1", "lab.operator");
        stockVoucherService.approve(quarantine.id(), "admin.operator");

        StockVoucherDtos.Response excessiveIssue = stockVoucherService.create(
                request(StockVoucherType.ISSUE, stock.sku(), 4, 0, null), "issue-key-1", "lab.operator");
        assertStatus(HttpStatus.CONFLICT,
                () -> stockVoucherService.approve(excessiveIssue.id(), "admin.operator"));
        assertEquals("PENDING", stockVoucherService.getVoucher(excessiveIssue.id()).status());
        assertBalances(stock.sku(), 5, 2, 5, 2);

        StockVoucherDtos.Response validIssue = stockVoucherService.create(
                request(StockVoucherType.ISSUE, stock.sku(), 3, 0, null), "issue-key-2", "lab.operator");
        stockVoucherService.approve(validIssue.id(), "admin.operator");
        assertBalances(stock.sku(), 2, 2, 2, 2);
    }

    @Test
    void quarantinedUnitsCannotBeSoldOrTransferredFromTheirLocation() {
        TestStock stock = createStock(0, 5);
        StockVoucherDtos.Response quarantine = stockVoucherService.create(
                new StockVoucherDtos.CreateRequest(
                        StockVoucherType.QUARANTINE,
                        InventoryService.BRANCH_01,
                        null,
                        "damaged stock",
                        null,
                        List.of(new StockVoucherDtos.ItemRequest(stock.sku(), 2, 0))),
                "branch-defect-key-1",
                "lab.operator");
        stockVoucherService.approve(quarantine.id(), "admin.operator");

        assertStatus(HttpStatus.CONFLICT, () -> inventoryService.checkAvailability(
                new StockCheckRequest(
                        InventoryService.BRANCH_01,
                        List.of(new StockCheckRequest.Item(stock.sku(), 4)))));
        assertDoesNotThrow(() -> inventoryService.checkAvailability(
                new StockCheckRequest(
                        InventoryService.BRANCH_01,
                        List.of(new StockCheckRequest.Item(stock.sku(), 3)))));

        CreateStockTransferRequest transfer = new CreateStockTransferRequest();
        transfer.setSourceLocationCode(InventoryService.BRANCH_01);
        transfer.setDestinationLocationCode(InventoryService.LAB);
        transfer.setReason("quarantine test");
        transfer.setItems(List.of(transferItem(stock.sku(), 4)));
        assertStatus(HttpStatus.CONFLICT, () -> inventoryService.createTransfer(transfer, "lab.operator"));
        assertBalances(stock.sku(), 0, 0, 5, 2);
    }

    @Test
    void failedMultiSkuIssueLeavesEveryBalanceAndMovementUnchanged() {
        TestStock first = createStock(5);
        TestStock second = createStock(1);
        StockVoucherDtos.CreateRequest request = new StockVoucherDtos.CreateRequest(
                StockVoucherType.ISSUE,
                InventoryService.LAB,
                null,
                "atomicity test",
                null,
                List.of(
                        new StockVoucherDtos.ItemRequest(first.sku(), 2, 0),
                        new StockVoucherDtos.ItemRequest(second.sku(), 2, 0)));
        StockVoucherDtos.Response pending = stockVoucherService.create(request, "issue-atomic-key", "lab.operator");

        assertStatus(HttpStatus.CONFLICT, () -> stockVoucherService.approve(pending.id(), "admin.operator"));

        assertEquals("PENDING", stockVoucherService.getVoucher(pending.id()).status());
        assertBalances(first.sku(), 5, 0, 5, 0);
        assertBalances(second.sku(), 1, 0, 1, 0);
        assertTrue(movementRepository.findAllByReferenceTypeAndReferenceId(
                "STOCK_VOUCHER", pending.voucherCode()).isEmpty());
    }

    @Test
    void concurrentApprovalsApplyOneReceiptAndRecordOneSetOfMovements() throws Exception {
        TestStock stock = createStock(2);
        StockVoucherDtos.Response pending = stockVoucherService.create(
                request(StockVoucherType.RECEIPT, stock.sku(), 3, 0, null), "receipt-key-3", "lab.operator");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> approveAfter(start, pending.id()));
            Future<Boolean> second = executor.submit(() -> approveAfter(start, pending.id()));
            start.countDown();
            assertEquals(1, (int) List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue).count());
        } finally {
            executor.shutdownNow();
        }

        assertBalances(stock.sku(), 5, 0, 5, 0);
        assertEquals(1, movementRepository.findAllByReferenceTypeAndReferenceId(
                "STOCK_VOUCHER", pending.voucherCode()).size());
    }

    @Test
    void supplierCodesAreUniqueAndSupplierCanBeDisabled() {
        SupplierDtos.Request request = new SupplierDtos.Request(
                "sup-1", "Supplier One", null, null, null, null);
        SupplierDtos.Response supplier = supplierService.create(request);
        assertEquals("SUP-1", supplier.code());
        assertStatus(HttpStatus.CONFLICT, () -> supplierService.create(
                new SupplierDtos.Request("sup-1", "Duplicate", null, null, null, null)));

        SupplierDtos.Response disabled = supplierService.setActive(supplier.id(), false);
        assertFalse(disabled.active());
    }

    private boolean approveAfter(CountDownLatch start, Long voucherId) throws Exception {
        start.await();
        try {
            stockVoucherService.approve(voucherId, "admin.operator");
            return true;
        } catch (ResponseStatusException exception) {
            assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
            return false;
        }
    }

    private StockVoucherDtos.CreateRequest request(
            StockVoucherType type, String sku, int quantity, int quarantinedQuantity, Long supplierId) {
        return new StockVoucherDtos.CreateRequest(
                type,
                InventoryService.LAB,
                supplierId,
                "integration test",
                null,
                List.of(new StockVoucherDtos.ItemRequest(sku, quantity, quarantinedQuantity)));
    }

    private TestStock createStock(int labQuantity) {
        return createStock(labQuantity, 0);
    }

    private TestStock createStock(int labQuantity, int branchQuantity) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Brand brand = brandRepository.save(Brand.builder().name("Voucher-" + suffix).build());
        Category category = categoryRepository.save(Category.builder()
                .name("Voucher-" + suffix)
                .slug("voucher-" + suffix)
                .build());
        Product product = productRepository.save(Product.builder()
                .name("Voucher " + suffix)
                .slug("voucher-product-" + suffix)
                .category(category)
                .brand(brand)
                .productType(ProductType.FRAME)
                .build());
        String sku = "VC-" + suffix.substring(0, 20);
        ProductVariant variant = variantRepository.save(ProductVariant.builder()
                .product(product)
                .sku(sku)
                .barcode("BAR-" + suffix)
                .name("Voucher item")
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

    private CreateStockTransferRequest.Item transferItem(String sku, int quantity) {
        CreateStockTransferRequest.Item item = new CreateStockTransferRequest.Item();
        item.setSku(sku);
        item.setQuantity(quantity);
        return item;
    }

    private void assertBalances(String sku, int expectedLabQuantity, int expectedLabQuarantined,
                                int expectedVariantQuantity, int expectedVariantQuarantined) {
        InventoryBalance lab = balanceRepository.findBySkuAndLocationCode(sku, InventoryService.LAB).orElseThrow();
        ProductVariant variant = variantRepository.findBySku(sku).orElseThrow();
        assertEquals(expectedLabQuantity, lab.getQuantity());
        assertEquals(expectedLabQuarantined, lab.getQuarantinedQuantity());
        assertEquals(expectedVariantQuantity, variant.getStockQuantity());
        assertEquals(expectedVariantQuarantined, variant.getNonSellableQuantity());
    }

    private int countMovementType(List<InventoryMovement> movements, String type) {
        return (int) movements.stream().filter(movement -> movement.getMovementType().equals(type)).count();
    }

    private void assertStatus(HttpStatus expected, Runnable action) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, exception.getStatusCode());
    }

    private record TestStock(String sku) {}
}
