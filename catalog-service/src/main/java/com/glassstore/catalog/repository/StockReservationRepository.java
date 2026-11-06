package com.glassstore.catalog.repository;

import com.glassstore.catalog.entity.ReservationStatus;
import com.glassstore.catalog.entity.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {
    Optional<StockReservation> findByReservationKey(String reservationKey);
    List<StockReservation> findAllByReservationKey(String reservationKey);
    // Read the current hold under a write lock before mutating stock. A retry can arrive
    // while an earlier remote call is still committing after the caller timed out.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    List<StockReservation> findByOrderCodeAndStatus(String orderCode, ReservationStatus status);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    List<StockReservation> findByReservationKeyAndStatus(String reservationKey, ReservationStatus status);
}
