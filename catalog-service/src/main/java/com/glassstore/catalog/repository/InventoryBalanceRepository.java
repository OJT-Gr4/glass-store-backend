package com.glassstore.catalog.repository;

import com.glassstore.catalog.entity.InventoryBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, Long> {

    @Query("""
            select b from InventoryBalance b
            join fetch b.variant v
            join fetch v.product p
            join fetch b.location l
            order by v.sku, l.code
            """)
    List<InventoryBalance> findAllWithDetails();

    @Query("""
            select b from InventoryBalance b
            where b.variant.sku = :sku and b.location.code = :locationCode
            """)
    Optional<InventoryBalance> findBySkuAndLocationCode(
            @Param("sku") String sku,
            @Param("locationCode") String locationCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from InventoryBalance b
            where b.variant.sku = :sku and b.location.code = :locationCode
            """)
    Optional<InventoryBalance> findBySkuAndLocationCodeForUpdate(
            @Param("sku") String sku,
            @Param("locationCode") String locationCode);

    @Query("""
            select b from InventoryBalance b
            join fetch b.variant v
            join fetch v.product p
            join fetch b.location l
            where b.quarantinedQuantity > 0
            order by l.code, v.sku
            """)
    List<InventoryBalance> findAllWithQuarantinedStock();

    boolean existsByVariant_IdAndLocation_Id(Long variantId, Long locationId);
    boolean existsByLocation_Id(Long locationId);

    @Modifying
    @Query("""
            update InventoryBalance b
            set b.quantity = b.quantity - :quantity
            where b.variant.sku = :sku
              and b.location.code = :locationCode
              and b.quantity - b.quarantinedQuantity >= :quantity
            """)
    int decrementIfAvailable(
            @Param("sku") String sku,
            @Param("locationCode") String locationCode,
            @Param("quantity") int quantity);

    @Modifying
    @Query("""
            update InventoryBalance b
            set b.quantity = b.quantity + :quantity
            where b.variant.sku = :sku
              and b.location.code = :locationCode
            """)
    int increment(
            @Param("sku") String sku,
            @Param("locationCode") String locationCode,
            @Param("quantity") int quantity);
}
