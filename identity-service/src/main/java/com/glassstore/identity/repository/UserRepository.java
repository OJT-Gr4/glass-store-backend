package com.glassstore.identity.repository;

import com.glassstore.identity.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    Optional<User> findByEmployeeCode(String employeeCode);
    Optional<User> findFirstByPhoneNumber(String phoneNumber);
    List<User> findByPhoneNumber(String phoneNumber);

    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r.name = :roleName")
    List<User> findByRoleName(@Param("roleName") String roleName);

    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r.name <> 'ROLE_CUSTOMER'")
    List<User> findAllStaff();

    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r.name = 'ROLE_CUSTOMER'")
    List<User> findAllCustomers();

    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r.name = 'ROLE_CUSTOMER' AND (" +
           "LOWER(u.fullName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "u.phoneNumber LIKE CONCAT('%', :query, '%') OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(u.employeeCode) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<User> searchCustomers(@Param("query") String query);
}
