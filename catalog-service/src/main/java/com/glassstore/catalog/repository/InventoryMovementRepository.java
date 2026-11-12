package com.glassstore.catalog.repository;

import com.glassstore.catalog.entity.InventoryMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    java.util.List<InventoryMovement> findAllByReferenceTypeAndReferenceId(
            String referenceType, String referenceId);

    java.util.List<InventoryMovement> findAllByReferenceType(String referenceType);

    @Query("""
            select movement from InventoryMovement movement
            where (:sku is null or movement.sku = :sku)
              and (:locationCode is null
                   or movement.sourceLocationCode = :locationCode
                   or movement.destinationLocationCode = :locationCode)
              and (:referenceId is null or movement.referenceId = :referenceId)
            """)
    Page<InventoryMovement> findByFilters(
            @Param("sku") String sku,
            @Param("locationCode") String locationCode,
            @Param("referenceId") String referenceId,
            Pageable pageable);
}
