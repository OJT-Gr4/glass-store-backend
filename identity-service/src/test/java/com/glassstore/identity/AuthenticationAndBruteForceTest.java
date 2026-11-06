package com.glassstore.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.identity.dto.LoginRequest;
import com.glassstore.identity.entity.Role;
import com.glassstore.identity.entity.User;
import com.glassstore.identity.repository.RoleRepository;
import com.glassstore.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class AuthenticationAndBruteForceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testCustomer;

    @BeforeEach
    void setUp() {
        Role customerRole = roleRepository.findByName("ROLE_CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_CUSTOMER").description("Khách hàng").build()));

        testCustomer = userRepository.findByEmail("brute_force_user@example.com")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("brute_force_user")
                        .email("brute_force_user@example.com")
                        .passwordHash(passwordEncoder.encode("CorrectPassword123!"))
                        .fullName("Người Dùng Brute Force")
                        .status("ACTIVE")
                        .failedAttempts(0)
                        .lockoutExpiration(null)
                        .roles(new HashSet<>(Collections.singletonList(customerRole)))
                        .build()));

        // Reset trạng thái trước mỗi test
        testCustomer.setFailedAttempts(0);
        testCustomer.setLockoutExpiration(null);
        testCustomer.setStatus("ACTIVE");
        userRepository.save(testCustomer);
    }

    @Test
    void commonDemoPasswordsCannotBypassTheStoredHash() throws Exception {
        for (String rejected : new String[]{"123456", "Password2026@", " CorrectPassword123! "}) {
            LoginRequest request = LoginRequest.builder()
                    .email("brute_force_user@example.com").password(rejected).build();
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("1. Đăng nhập khách hàng thành công bằng Email và Mật khẩu")
    void testLoginSuccess() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("brute_force_user@example.com")
                .password("CorrectPassword123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(86400))
                .andExpect(jsonPath("$.data.email").value("brute_force_user@example.com"))
                .andExpect(jsonPath("$.data.roles[0]").value("ROLE_CUSTOMER"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    @DisplayName("2. Đăng nhập sai mật khẩu: Nhận lỗi HTTP 401 Unauthorized")
    void testLoginWrongPassword() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("brute_force_user@example.com")
                .password("WrongPassword123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Email hoặc mật khẩu không chính xác!"));

        User user = userRepository.findByEmail("brute_force_user@example.com").orElseThrow();
        assertEquals(1, user.getFailedAttempts());
    }

    @Test
    @DisplayName("3. Chống Brute Force (AC4): Nhập sai liên tiếp 5 lần -> Lần thứ 6 bị khóa tạm thời trong 15 phút (HTTP 423)")
    void testBruteForceProtectionLockout() throws Exception {
        LoginRequest wrongRequest = LoginRequest.builder()
                .email("brute_force_user@example.com")
                .password("WrongPassword!")
                .build();

        // 4 lần đầu nhập sai
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(wrongRequest)))
                    .andExpect(status().isUnauthorized());
        }

        // Lần thứ 5 nhập sai -> Kích hoạt khóa tài khoản 15 phút
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongRequest)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value(423))
                .andExpect(jsonPath("$.message").value("Bạn đã nhập sai mật khẩu 5 lần. Tài khoản tạm thời bị khóa trong 15 phút!"));

        User userAfter5 = userRepository.findByEmail("brute_force_user@example.com").orElseThrow();
        assertEquals(5, userAfter5.getFailedAttempts());
        assertNotNull(userAfter5.getLockoutExpiration());
        assertTrue(userAfter5.isLocked());

        // Lần thứ 6 thực hiện đăng nhập (kể cả với mật khẩu đúng!) -> Vẫn bị từ chối do đang bị khóa
        LoginRequest correctRequest = LoginRequest.builder()
                .email("brute_force_user@example.com")
                .password("CorrectPassword123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctRequest)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value(423));
    }

    @Test
    @DisplayName("4. Tự động mở khóa sau 15 phút: Có thể đăng nhập bình thường trở lại")
    void testAutoUnlockAfter15Minutes() throws Exception {
        // Mô phỏng tài khoản bị khóa trong quá khứ (> 15 phút trước)
        testCustomer.setFailedAttempts(5);
        testCustomer.setLockoutExpiration(LocalDateTime.now().minusMinutes(16));
        userRepository.save(testCustomer);

        // Đăng nhập lại với mật khẩu đúng
        LoginRequest correctRequest = LoginRequest.builder()
                .email("brute_force_user@example.com")
                .password("CorrectPassword123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        // Sau khi đăng nhập thành công, bộ đếm sai được reset về 0 và lockoutExpiration = null
        User unlockedUser = userRepository.findByEmail("brute_force_user@example.com").orElseThrow();
        assertEquals(0, unlockedUser.getFailedAttempts());
        assertNull(unlockedUser.getLockoutExpiration());
        assertFalse(unlockedUser.isLocked());
    }

    @Test
    @DisplayName("5. Đăng nhập thành công reset bộ đếm lần nhập sai về 0")
    void testLoginSuccessResetsFailedAttempts() throws Exception {
        // Giả lập nhập sai 2 lần
        testCustomer.setFailedAttempts(2);
        userRepository.save(testCustomer);

        // Đăng nhập đúng mật khẩu
        LoginRequest correctRequest = LoginRequest.builder()
                .email("brute_force_user@example.com")
                .password("CorrectPassword123!")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctRequest)))
                .andExpect(status().isOk());

        User resetUser = userRepository.findByEmail("brute_force_user@example.com").orElseThrow();
        assertEquals(0, resetUser.getFailedAttempts());
    }
}

