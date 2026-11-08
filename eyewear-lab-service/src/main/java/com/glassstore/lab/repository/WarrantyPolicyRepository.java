package com.glassstore.lab.repository;

import com.glassstore.lab.entity.WarrantyPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WarrantyPolicyRepository extends JpaRepository<WarrantyPolicy, Long> {
    Optional<WarrantyPolicy> findFirstByBenefitTypeAndActiveTrueOrderByVersionDesc(String benefitType);

    List<WarrantyPolicy> findAllByActiveTrueOrderByBenefitTypeAscVersionDesc();

    List<WarrantyPolicy> findAllByBenefitTypeOrderByVersionDesc(String benefitType);
}

