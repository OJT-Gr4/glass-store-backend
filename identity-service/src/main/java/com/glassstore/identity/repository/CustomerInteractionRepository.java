package com.glassstore.identity.repository;

import com.glassstore.identity.entity.CustomerInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerInteractionRepository extends JpaRepository<CustomerInteraction, Long> {
    List<CustomerInteraction> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
}
