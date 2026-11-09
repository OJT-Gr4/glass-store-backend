package com.glassstore.catalog.repository;

import com.glassstore.catalog.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    List<Supplier> findAllByOrderByCodeAsc();
    List<Supplier> findAllByActiveTrueOrderByCodeAsc();
    Optional<Supplier> findByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
