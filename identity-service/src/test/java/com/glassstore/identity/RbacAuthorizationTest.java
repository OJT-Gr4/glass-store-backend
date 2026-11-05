package com.glassstore.identity;

import com.glassstore.identity.entity.Role;
import com.glassstore.identity.entity.User;
import com.glassstore.identity.repository.RoleRepository;
import com.glassstore.identity.repository.UserRepository;
import com.glassstore.identity.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.HashSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class RbacAuthorizationTest {

    @Test
    void staffCreationRejectsAnonymousAndCustomers() throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/staff")
                .contentType("application/json").content("{}");
        mockMvc.perform(request).andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/staff")
                .header("Authorization","Bearer "+customerToken).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotReadAnotherUserProfile() throws Exception {
        var own=userRepository.findByEmail("customer_test@example.com").orElseThrow();
        var other=userRepository.findByEmail("staff_test@optivision.vn").orElseThrow();
        mockMvc.perform(get("/api/users/"+own.getId()).header("Authorization","Bearer "+customerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/"+other.getId()).header("Authorization","Bearer "+customerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateStaffButCannotMintServiceRoles() throws Exception {
        String unique="day3-"+System.nanoTime();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/staff")
                .header("Authorization","Bearer "+adminToken).contentType("application/json")
                .content("{\"username\":\""+unique+"\",\"email\":\""+unique+"@example.com\",\"fullName\":\"QA Staff\",\"roleName\":\"ROLE_SALES_STAFF\",\"password\":\"QaPassword123!\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/users/staff")
                .header("Authorization","Bearer "+adminToken).contentType("application/json")
                .content("{\"email\":\"forged-role@example.com\",\"roleName\":\"ROLE_SERVICE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String adminToken;
    private String staffToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.findByName("ROLE_ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_ADMIN").build()));
        Role staffRole = roleRepository.findByName("ROLE_SALES_STAFF")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_SALES_STAFF").build()));
        Role customerRole = roleRepository.findByName("ROLE_CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_CUSTOMER").build()));

        User adminUser = userRepository.findByEmail("admin_test@optivision.vn")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("admin_test")
                        .email("admin_test@optivision.vn")
                        .passwordHash(passwordEncoder.encode("Password123!"))
                        .fullName("Admin Test")
                        .status("ACTIVE")
                        .roles(new HashSet<>(Collections.singletonList(adminRole)))
                        .build()));

        User staffUser = userRepository.findByEmail("staff_test@optivision.vn")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("staff_test")
                        .email("staff_test@optivision.vn")
                        .passwordHash(passwordEncoder.encode("Password123!"))
                        .fullName("Staff POS Test")
                        .status("ACTIVE")
                        .roles(new HashSet<>(Collections.singletonList(staffRole)))
                        .build()));

        User customerUser = userRepository.findByEmail("customer_test@example.com")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("customer_test")
                        .email("customer_test@example.com")
                        .passwordHash(passwordEncoder.encode("Password123!"))
                        .fullName("Customer Test")
                        .status("ACTIVE")
                        .roles(new HashSet<>(Collections.singletonList(customerRole)))
                        .build()));

        adminToken = jwtTokenProvider.generateAccessToken(adminUser);
        staffToken = jwtTokenProvider.generateAccessToken(staffUser);
        customerToken = jwtTokenProvider.generateAccessToken(customerUser);
    }

    @Test
    @DisplayName("1. ADMIN có quyền truy cập danh sách nhân viên nội bộ (/api/v1/users/staff): 200 OK")
    void testAdminAccessStaffList() throws Exception {
        mockMvc.perform(get("/api/v1/users/staff")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("2. CUSTOMER không được truy cập API nội bộ nhân viên (/api/v1/users/staff): Nhận HTTP 403 Forbidden")
    void testCustomerCannotAccessStaffList() throws Exception {
        mockMvc.perform(get("/api/v1/users/staff")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thực hiện thao tác này!"));
    }

    @Test
    @DisplayName("3. SALES_STAFF không có quyền ADMIN quản trị nhân sự (/api/v1/users/staff): Nhận HTTP 403 Forbidden")
    void testSalesStaffCannotManageStaff() throws Exception {
        mockMvc.perform(get("/api/v1/users/staff")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("4. SALES_STAFF có quyền tra cứu danh sách khách hàng (/api/v1/users/customers): 200 OK")
    void testSalesStaffCanAccessCustomerList() throws Exception {
        mockMvc.perform(get("/api/v1/users/customers")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}

