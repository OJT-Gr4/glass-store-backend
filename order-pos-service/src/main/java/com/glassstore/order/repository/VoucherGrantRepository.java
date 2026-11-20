package com.glassstore.order.repository;

import com.glassstore.order.entity.VoucherGrant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface VoucherGrantRepository extends JpaRepository<VoucherGrant, Long> {
    List<VoucherGrant> findByCustomerIdAndRevokedFalseAndUsedOrderIdIsNullAndReservedOrderIdIsNull(Long customerId);
    List<VoucherGrant> findByVoucherIdOrderByGrantedAtDesc(Long voucherId);
    Optional<VoucherGrant> findByVoucherIdAndCustomerId(Long voucherId, Long customerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from VoucherGrant g where g.voucherId = :voucherId and g.reservedOrderId is not null and g.usedOrderId is null")
    List<VoucherGrant> lockReservations(@Param("voucherId") Long voucherId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from VoucherGrant g where g.voucherId = :voucherId and g.customerId = :customerId")
    Optional<VoucherGrant> lockGrant(@Param("voucherId") Long voucherId, @Param("customerId") Long customerId);
    Optional<VoucherGrant> findByReservedOrderId(Long orderId);
}
