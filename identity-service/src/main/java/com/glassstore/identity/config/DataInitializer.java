package com.glassstore.identity.config;

import com.glassstore.identity.entity.Role;
import com.glassstore.identity.entity.User;
import com.glassstore.identity.repository.RoleRepository;
import com.glassstore.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "glassstore.demo.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Identity database đã có sẵn {} tài khoản người dùng từ MySQL, bỏ qua nạp seed DataInitializer.", userRepository.count());
            return;
        }

        log.info("Khởi tạo và đồng bộ seed data danh sách tài khoản hệ thống (Không sử dụng OTP)...");

        // 1. Roles
        Role adminRole = roleRepository.findByName("ROLE_ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_ADMIN").description("Quản trị viên toàn hệ thống").build()));
        Role staffRole = roleRepository.findByName("ROLE_SALES_STAFF")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_SALES_STAFF").description("Nhân viên bán hàng & Thu ngân POS").build()));
        Role optomRole = roleRepository.findByName("ROLE_OPTOMETRIST")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_OPTOMETRIST").description("Bác sĩ / KTV đo khám khúc xạ có CCHN").build()));
        Role labRole = roleRepository.findByName("ROLE_LAB_TECH")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_LAB_TECH").description("Kỹ thuật viên xưởng gia công mài lắp kính").build()));
        Role customerRole = roleRepository.findByName("ROLE_CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_CUSTOMER").description("Khách hàng mua sắm đa kênh").build()));

        String defaultPassHash = passwordEncoder.encode("123456");

        // 2. Nội bộ (Admin, POS, Optometry, Lab)
        // 2.1 Admin
        User admin = userRepository.findByUsername("admin").orElse(User.builder().username("admin").build());
        admin.setEmail("adminoptic@gmail.com");
        admin.setFullName("Admin");
        admin.setPasswordHash(defaultPassHash);
        admin.setPhoneNumber("0901000001");
        admin.setEmployeeCode("ADM-001");
        admin.setBranchName("Trụ Sở Chính (HQ TP.HCM)");
        admin.setStatus("ACTIVE");
        admin.setFailedAttempts(0);
        admin.setRoles(new HashSet<>(Collections.singletonList(adminRole)));
        userRepository.save(admin);

        // 2.2 Thu ngân / Bán hàng POS (Nguyễn Văn Nam)
        User pos = userRepository.findByUsername("pos")
                .or(() -> userRepository.findByUsername("nv_pos"))
                .or(() -> userRepository.findByUsername("staff"))
                .orElse(User.builder().username("pos").build());
        pos.setUsername("pos");
        pos.setEmail("posoptic@gmail.com");
        pos.setFullName("Nguyễn Văn Nam");
        pos.setPasswordHash(defaultPassHash);
        pos.setPhoneNumber("0903222333");
        pos.setEmployeeCode("NV-001");
        pos.setBranchName("Chi nhánh 01 - Lê Thánh Tôn, Quận 1");
        pos.setStatus("ACTIVE");
        pos.setFailedAttempts(0);
        pos.setRoles(new HashSet<>(Collections.singletonList(staffRole)));
        userRepository.save(pos);

        // 2.3 Khúc xạ (Lê Thị Ánh Tuyết)
        User optometry = userRepository.findByUsername("optometry")
                .or(() -> userRepository.findByUsername("bs_tuyet"))
                .or(() -> userRepository.findByUsername("optometrist"))
                .orElse(User.builder().username("optometry").build());
        optometry.setUsername("optometry");
        optometry.setEmail("optometryoptic@gmail.com");
        optometry.setFullName("Lê Thị Ánh Tuyết");
        optometry.setPasswordHash(defaultPassHash);
        optometry.setPhoneNumber("0903444555");
        optometry.setEmployeeCode("KTV-001");
        optometry.setBranchName("Phòng Khúc Xạ 01 - Lê Thánh Tôn, Quận 1");
        optometry.setStatus("ACTIVE");
        optometry.setFailedAttempts(0);
        optometry.setRoles(new HashSet<>(Collections.singletonList(optomRole)));
        userRepository.save(optometry);

        // 2.4 KTV Lab (Trần Văn Tuấn)
        User lab = userRepository.findByUsername("lab")
                .or(() -> userRepository.findByUsername("ktv_lab"))
                .or(() -> userRepository.findByUsername("labtech"))
                .orElse(User.builder().username("lab").build());
        lab.setUsername("lab");
        lab.setEmail("laboptic@gmail.com");
        lab.setFullName("Trần Văn Tuấn");
        lab.setPasswordHash(defaultPassHash);
        lab.setPhoneNumber("0903666777");
        lab.setEmployeeCode("LAB-001");
        lab.setBranchName("Xưởng Gia Công Lab Kính - Chi nhánh Quận 1");
        lab.setStatus("ACTIVE");
        lab.setFailedAttempts(0);
        lab.setRoles(new HashSet<>(Collections.singletonList(labRole)));
        userRepository.save(lab);

        // 3. Khách hàng mẫu
        User cusDuy = userRepository.findByUsername("cus_duy")
                .or(() -> userRepository.findByEmail("duy.nguyen@example.com"))
                .orElse(User.builder().username("cus_duy").build());
        cusDuy.setUsername("cus_duy");
        cusDuy.setEmail("duy.nguyen@example.com");
        cusDuy.setPasswordHash(defaultPassHash);
        cusDuy.setFullName("Nguyễn Hoàng Duy");
        cusDuy.setPhoneNumber("0918732998");
        cusDuy.setEmployeeCode("KH-VIP-001");
        cusDuy.setBranchName("120 Hai Bà Trưng, P. Đa Kao, Q.1, TP.HCM");
        cusDuy.setStatus("ACTIVE");
        cusDuy.setFailedAttempts(0);
        cusDuy.setRoles(new HashSet<>(Collections.singletonList(customerRole)));
        userRepository.save(cusDuy);

        User cusTest = userRepository.findByUsername("customer")
                .or(() -> userRepository.findByEmail("customer@example.com"))
                .orElse(User.builder().username("customer").build());
        cusTest.setUsername("customer");
        cusTest.setEmail("customer@example.com");
        cusTest.setPasswordHash(passwordEncoder.encode("Password123!"));
        cusTest.setFullName("Nguyễn Văn A");
        cusTest.setPhoneNumber("0912345678");
        cusTest.setStatus("ACTIVE");
        cusTest.setFailedAttempts(0);
        cusTest.setRoles(new HashSet<>(Collections.singletonList(customerRole)));
        userRepository.save(cusTest);

        log.info("Nạp seed data thành công: Admin, Staff, Optometry, Lab, và Customer!");
    }
}
