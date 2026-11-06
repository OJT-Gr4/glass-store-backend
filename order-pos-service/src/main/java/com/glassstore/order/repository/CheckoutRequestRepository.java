package com.glassstore.order.repository;

import com.glassstore.order.entity.CheckoutRequestRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface CheckoutRequestRepository extends JpaRepository<CheckoutRequestRecord,String> {
    // Atomic insert-or-lock: concurrent first submissions wait on the same unique key.
    @Modifying
    @Query(value = "INSERT INTO checkout_requests (request_key,request_hash,owner,created_at) VALUES (:key,:hash,:owner,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE request_key=request_key", nativeQuery = true)
    void claim(@Param("key") String key,@Param("hash") String hash,@Param("owner") String owner);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CheckoutRequestRecord r where r.requestKey=:key")
    Optional<CheckoutRequestRecord> lockByKey(@Param("key") String key);
}
