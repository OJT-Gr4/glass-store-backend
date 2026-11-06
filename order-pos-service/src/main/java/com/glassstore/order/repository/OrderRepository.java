package com.glassstore.order.repository;

import com.glassstore.order.domain.OrderStatus;
import com.glassstore.order.domain.SalesChannel;
import com.glassstore.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.id=:id")
    Optional<Order> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Order> findByOrderCode(String orderCode);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.orderCode=:code")
    Optional<Order> lockByOrderCode(@org.springframework.data.repository.query.Param("code") String code);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    List<Order> findAllByOrderByCreatedAtDesc();
    List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    List<Order> findByChannelOrderByCreatedAtDesc(SalesChannel channel);

    List<Order> findByStatusAndChannelOrderByCreatedAtDesc(OrderStatus status, SalesChannel channel);

    List<Order> findByPosSessionIdOrderByCreatedAtDesc(Long posSessionId);

    long countByCustomerIdAndStatusNot(Long customerId, OrderStatus status);
}
