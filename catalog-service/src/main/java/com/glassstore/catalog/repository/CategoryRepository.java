package com.glassstore.catalog.repository;

import com.glassstore.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findBySlug(String slug);
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
    boolean existsBySlugIgnoreCase(String slug);
    boolean existsBySlugIgnoreCaseAndIdNot(String slug, Long id);
}
