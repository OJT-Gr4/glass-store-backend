package com.glassstore.order.repository;
import com.glassstore.order.entity.LoyaltyAccount;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount,Long> {
 @Modifying @Query(value="INSERT INTO loyalty_accounts(customer_id,balance,version) VALUES (:id,0,0) ON DUPLICATE KEY UPDATE customer_id=customer_id",nativeQuery=true)
 void ensureRow(@Param("id") Long id);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from LoyaltyAccount a where a.customerId=:id") Optional<LoyaltyAccount> lockByCustomerId(@Param("id") Long id);
}

