package com.glassstore.identity.config;

import com.glassstore.identity.entity.*;
import com.glassstore.identity.repository.RoleRepository;
import com.glassstore.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Checking & seeding default roles and employee accounts...");

        // 1. Initialize Roles
        Role adminRole = getOrCreateRole(RoleType.ROLE_ADMIN.name(), "Quản trị viên toàn hệ thống");
        Role staffRole = getOrCreateRole(RoleType.ROLE_SALES_STAFF.name(), "Nhân viên bán hàng tại quầy POS");
        Role optoRole = getOrCreateRole(RoleType.ROLE_OPTOMETRIST.name(), "Kỹ thuật viên đo mắt khúc xạ");
        Role labRole = getOrCreateRole(RoleType.ROLE_LAB_TECH.name(), "Kỹ thuật viên xưởng gia công mài lắp kính");
        Role customerRole = getOrCreateRole(RoleType.ROLE_CUSTOMER.name(), "Khách hàng mua sắm online");

        // 2. Initialize Default Users if not present
        String defaultEncodedPassword = passwordEncoder.encode("123456");

        createUserIfNotExists("admin@glassstore.com", "admin", "Quản Trị Viên", "0901000001", defaultEncodedPassword,
                Set.of(adminRole));
        createUserIfNotExists("staff@glassstore.com", "staff", "Nguyễn Bán Hàng (POS)", "0901000002",
                defaultEncodedPassword, Set.of(staffRole));
        createUserIfNotExists("optometrist@glassstore.com", "optometrist", "Bác Sĩ Trần Đo Mắt", "0901000003",
                defaultEncodedPassword, Set.of(optoRole));
        createUserIfNotExists("lab@glassstore.com", "labtech", "Lê Kỹ Thuật Xưởng Mài", "0901000004",
                defaultEncodedPassword, Set.of(labRole));
        createUserIfNotExists("customer@gmail.com", "customer", "Phạm Khách Hàng", "0987654321", defaultEncodedPassword,
                Set.of(customerRole));

        log.info("Default roles & users initialization complete.");
    }

    private Role getOrCreateRole(String name, String description) {
        return roleRepository.findByName(name)
                .orElseGet(() -> roleRepository.save(new Role(name, description)));
    }

    private void createUserIfNotExists(String email, String username, String fullName, String phone, String password,
            Set<Role> roles) {
        if (!userRepository.existsByEmail(email)) {
            User user = User.builder()
                    .email(email)
                    .username(username)
                    .fullName(fullName)
                    .phoneNumber(phone)
                    .password(password)
                    .status(UserStatus.ACTIVE)
                    .failedLoginAttempts(0)
                    .roles(roles)
                    .build();
            userRepository.save(user);
            log.info("Created default user: {} with roles: {}", email, roles.stream().map(Role::getName).toList());
        }
    }
}
