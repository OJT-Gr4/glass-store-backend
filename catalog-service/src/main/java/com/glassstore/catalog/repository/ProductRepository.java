package com.glassstore.catalog.repository;

import com.glassstore.catalog.entity.Product;
import com.glassstore.catalog.entity.ProductType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    Optional<Product> findBySlug(String slug);
    List<Product> findByCategoryId(Long categoryId);
    List<Product> findByProductType(ProductType productType);
    List<Product> findByStatus(String status);
    List<Product> findByBrandId(Long brandId);
    boolean existsByCategory_Id(Long categoryId);
    boolean existsByBrand_Id(Long brandId);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
