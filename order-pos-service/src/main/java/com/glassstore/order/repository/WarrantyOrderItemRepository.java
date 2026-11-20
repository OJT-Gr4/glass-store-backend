package com.glassstore.order.repository;

import com.glassstore.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WarrantyOrderItemRepository extends JpaRepository<Order, Long> {

    @Query("select distinct o from Order o join fetch o.items order by o.createdAt desc")
    List<Order> findAllOrdersWithItems();

    @Query("select distinct o from Order o join fetch o.items where o.customerPhone = :phone order by o.createdAt desc")
    List<Order> findOrdersWithItemsByCustomerPhone(@Param("phone") String phone);

    @Query("select distinct o from Order o join fetch o.items i where i.skuId = :sku order by o.createdAt desc")
    List<Order> findOrdersWithItemsBySku(@Param("sku") String sku);
}
