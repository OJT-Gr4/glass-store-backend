package com.glassstore.catalog.repository;

import com.glassstore.catalog.entity.InventoryDeduction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryDeductionRepository extends JpaRepository<InventoryDeduction, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<InventoryDeduction> findByOrderId(Long orderId);

    @Query("select d from InventoryDeduction d where d.orderId = :orderId")
    Optional<InventoryDeduction> findUnlockedByOrderId(@Param("orderId") Long orderId);

    Optional<InventoryDeduction> findByIdempotencyKey(String idempotencyKey);
}
