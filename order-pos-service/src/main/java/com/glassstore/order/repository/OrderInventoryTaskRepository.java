package com.glassstore.order.repository;
import com.glassstore.order.entity.OrderInventoryTask;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface OrderInventoryTaskRepository extends JpaRepository<OrderInventoryTask,Long> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OrderInventoryTask t where t.orderId=:id")
    Optional<OrderInventoryTask> lockByOrderId(@Param("id") Long id);
    List<OrderInventoryTask> findTop20ByStatusOrderByUpdatedAtAsc(String status);
}
