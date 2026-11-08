package com.glassstore.catalog.repository;

import com.glassstore.catalog.entity.StockTransfer;
import com.glassstore.catalog.entity.StockTransferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    @EntityGraph(attributePaths = {"sourceLocation", "destinationLocation", "items"})
    List<StockTransfer> findAllByOrderByCreatedAtDesc();

    boolean existsBySourceLocation_IdOrDestinationLocation_Id(Long sourceLocationId, Long destinationLocationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select distinct t from StockTransfer t left join fetch t.items where t.id = :id")
    Optional<StockTransfer> findByIdForUpdate(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("""
            update StockTransfer transfer
            set transfer.status = :newStatus,
                transfer.receivedAt = :receivedAt,
                transfer.receivedBy = :receivedBy
            where transfer.id = :id and transfer.status = :expectedStatus
            """)
    int transitionToReceived(
            @Param("id") Long id,
            @Param("expectedStatus") StockTransferStatus expectedStatus,
            @Param("newStatus") StockTransferStatus newStatus,
            @Param("receivedAt") LocalDateTime receivedAt,
            @Param("receivedBy") String receivedBy);
}
