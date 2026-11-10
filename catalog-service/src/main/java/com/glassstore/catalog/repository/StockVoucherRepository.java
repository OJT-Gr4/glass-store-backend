package com.glassstore.catalog.repository;

import com.glassstore.catalog.entity.StockVoucher;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockVoucherRepository extends JpaRepository<StockVoucher, Long> {

    @EntityGraph(attributePaths = {"location", "supplier", "items"})
    List<StockVoucher> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"location", "supplier", "items"})
    Optional<StockVoucher> findWithDetailsById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select voucher from StockVoucher voucher where voucher.id = :id")
    Optional<StockVoucher> findByIdForUpdate(@Param("id") Long id);

    Optional<StockVoucher> findByIdempotencyKey(String idempotencyKey);

    boolean existsBySupplier_Id(Long supplierId);
}
