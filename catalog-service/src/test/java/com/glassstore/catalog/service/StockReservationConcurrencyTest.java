package com.glassstore.catalog.service;

import com.glassstore.catalog.dto.StockDtos;
import com.glassstore.catalog.entity.*;
import com.glassstore.catalog.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:reservation-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
class StockReservationConcurrencyTest {
    @Autowired StockService stock;
    @Autowired CategoryRepository categories;
    @Autowired BrandRepository brands;
    @Autowired ProductRepository products;
    @Autowired ProductVariantRepository variants;
    @Autowired StockMovementRepository movements;
    @MockBean com.glassstore.catalog.config.CatalogDataInitializer catalogSeed;
    @MockBean com.glassstore.catalog.config.InventoryDataInitializer inventorySeed;

    @Test void overlappingCommitRetriesDeductOnlyOnce() throws Exception { verifyConcurrent(false); }
    @Test void overlappingReleaseRetriesReturnTheHoldOnlyOnce() throws Exception { verifyConcurrent(true); }

    private void verifyConcurrent(boolean release) throws Exception {
        String key=UUID.randomUUID().toString(),sku="QA-"+key;
        var category=categories.saveAndFlush(Category.builder().name("QA").slug(key).build());
        var brand=brands.saveAndFlush(Brand.builder().name(key).build());
        var product=products.saveAndFlush(Product.builder().name("QA").slug(key).category(category).brand(brand).productType(ProductType.FRAME).build());
        var variant=variants.saveAndFlush(ProductVariant.builder().product(product).sku(sku).barcode(sku).name("QA")
            .price(BigDecimal.TEN).costPrice(BigDecimal.ONE).stockQuantity(20).build());
        stock.reserve(StockDtos.ReserveRequest.builder().reservationKey(key).orderCode(key).actor("QA")
            .lines(List.of(StockDtos.ReservationLine.builder().sku(sku).quantity(2).build())).build());
        ExecutorService pool=Executors.newFixedThreadPool(6);CountDownLatch start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<StockDtos.ReservationResult>>();
            for(int i=0;i<6;i++)futures.add(pool.submit(()->{
                start.await();
                return release?stock.release(StockDtos.ReleaseRequest.builder().reservationKey(key).actor("QA").reason("QA cancel").build())
                    :stock.commit(StockDtos.CommitRequest.builder().reservationKey(key).actor("QA").build());
            }));
            start.countDown();int firstApplications=0;
            for(var future:futures)if(!future.get(30,TimeUnit.SECONDS).isReplayed())firstApplications++;
            assertEquals(1,firstApplications);
        } finally {pool.shutdownNow();}
        var saved=variants.findById(variant.getId()).orElseThrow();
        assertEquals(release?20:18,saved.getStockQuantity());assertEquals(0,saved.getReservedQuantity());
        assertEquals(1,movements.findByReferenceOrderByCreatedAtAsc(key).stream()
            .filter(m->m.getMovementType()==(release?StockMovementType.RELEASE:StockMovementType.COMMIT)).count());
    }
}
