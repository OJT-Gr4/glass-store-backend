package com.glassstore.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glassstore.identity.entity.OtpChallenge;
import com.glassstore.identity.entity.Role;
import com.glassstore.identity.entity.User;
import com.glassstore.identity.repository.OtpChallengeRepository;
import com.glassstore.identity.repository.RoleRepository;
import com.glassstore.identity.repository.UserRepository;
import com.glassstore.identity.service.SmsGateway;
import com.glassstore.identity.service.impl.ConfigurableSmsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:otp-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
class OtpAndPasswordResetTest {

    static final Map<String, String> DISPATCHED_OTPS = new ConcurrentHashMap<>();
    static final AtomicBoolean SIMULATE_UNCONFIGURED_PROVIDER = new AtomicBoolean(false);

    @TestConfiguration
    static class TestSmsConfig {
        @Bean
        @Primary
        SmsGateway testSmsGateway(ObjectMapper objectMapper) {
            ConfigurableSmsGateway unconfiguredFallback = new ConfigurableSmsGateway(objectMapper);
            return (phoneNumber, otpCode, purpose, expiresAt) -> {
                if (SIMULATE_UNCONFIGURED_PROVIDER.get()) {
                    unconfiguredFallback.sendOtp(phoneNumber, otpCode, purpose, expiresAt);
                }
                DISPATCHED_OTPS.put(phoneNumber + ":" + purpose, otpCode);
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private OtpChallengeRepository otpChallengeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User customer;

    @BeforeEach
    void setUp() {
        DISPATCHED_OTPS.clear();
        SIMULATE_UNCONFIGURED_PROVIDER.set(false);
        otpChallengeRepository.deleteAll();

        Role customerRole = roleRepository.findByName("ROLE_CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_CUSTOMER").description("Khách hàng").build()));

        String email = "otp_user_" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        String phone = "09" + String.format("%08d", Math.abs(email.hashCode()) % 100_000_000);
        customer = userRepository.save(User.builder()
                .username("otp_" + UUID.randomUUID().toString().substring(0, 8))
                .email(email)
                .passwordHash(passwordEncoder.encode("InitialPass123!"))
                .fullName("OTP Test Customer")
                .phoneNumber(phone)
                .phoneVerified(false)
                .status("ACTIVE")
                .roles(new HashSet<>(Collections.singletonList(customerRole)))
                .build());
    }

    @Test
    @DisplayName("S3-01.2: Khi SMS provider chưa cấu hình, yêu cầu gửi OTP trả lỗi 503 rõ ràng và không lưu challenge giả")
    void unconfiguredSmsProviderFailsExplicitlyWithoutPersistingChallenge() throws Exception {
        SIMULATE_UNCONFIGURED_PROVIDER.set(true);

        mockMvc.perform(post("/api/v1/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "identifier", customer.getEmail(),
                                "phoneNumber", customer.getPhoneNumber(),
                                "purpose", "PHONE_VERIFICATION"
                        ))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SMS_PROVIDER_NOT_CONFIGURED")));

        assertThat(otpChallengeRepository.count()).isZero();
    }

    @Test
    @DisplayName("S3-01.1: Xác thực SĐT qua OTP băm mã, ẩn SĐT, dùng 1 lần và từ chối tái sử dụng")
    void phoneOtpVerificationHashesCodeAndEnforcesSingleUse() throws Exception {
        String sendResponse = mockMvc.perform(post("/api/v1/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "identifier", customer.getEmail(),
                                "phoneNumber", customer.getPhoneNumber(),
                                "purpose", "PHONE_VERIFICATION"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.challengeId").isNotEmpty())
                .andExpect(jsonPath("$.data.maskedPhone").value(org.hamcrest.Matchers.startsWith("*")))
                .andExpect(jsonPath("$.data.otpCode").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String rawOtp = DISPATCHED_OTPS.get(customer.getPhoneNumber() + ":PHONE_VERIFICATION");
        assertThat(rawOtp).matches("^\\d{6}$");
        assertThat(sendResponse).doesNotContain(rawOtp);

        JsonNode data = objectMapper.readTree(sendResponse).path("data");
        String challengeId = data.path("challengeId").asText();

        OtpChallenge storedChallenge = otpChallengeRepository.findByChallengeCode(challengeId).orElseThrow();
        assertThat(storedChallenge.getCodeHash()).startsWith("$2").doesNotContain(rawOtp);

        mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId,
                                "otpCode", rawOtp
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.phoneVerified").value(true));

        User updatedUser = userRepository.findById(customer.getId()).orElseThrow();
        assertThat(updatedUser.isPhoneVerified()).isTrue();

        // Tái sử dụng cùng mã OTP phải bị từ chối
        mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId,
                                "otpCode", rawOtp
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("đã được sử dụng")));
    }

    @Test
    @DisplayName("S3-01.1: Nhập sai OTP quá số lần quy định khóa phiên OTP; mã hết hạn hoặc tài khoản không tồn tại bị từ chối")
    void rejectsWrongAttemptsExpiredCodeAndUnknownAccount() throws Exception {
        // Tài khoản không tồn tại
        mockMvc.perform(post("/api/v1/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "identifier", "nonexistent@example.test",
                                "purpose", "PASSWORD_RESET"
                        ))))
                .andExpect(status().isNotFound());

        // Số điện thoại không khớp với tài khoản
        mockMvc.perform(post("/api/v1/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "identifier", customer.getEmail(),
                                "phoneNumber", "0999999999",
                                "purpose", "PASSWORD_RESET"
                        ))))
                .andExpect(status().isBadRequest());

        // Gửi OTP hợp lệ rồi thử sai 5 lần
        String sendResponse = mockMvc.perform(post("/api/v1/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "identifier", customer.getEmail(),
                                "purpose", "PHONE_VERIFICATION"
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String challengeId = objectMapper.readTree(sendResponse).path("data").path("challengeId").asText();
        String rawOtp = DISPATCHED_OTPS.get(customer.getPhoneNumber() + ":PHONE_VERIFICATION");
        String wrongOtp = "000000".equals(rawOtp) ? "111111" : "000000";

        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/api/v1/auth/otp/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "challengeId", challengeId,
                                    "otpCode", wrongOtp
                            ))))
                    .andExpect(status().isBadRequest());
        }

        // Lần sai thứ 5 -> 429 Too Many Requests
        mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId,
                                "otpCode", wrongOtp
                        ))))
                .andExpect(status().isTooManyRequests());

        // Kể cả nhập đúng mã sau khi đã quá số lần thử vẫn bị khóa (429)
        mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId,
                                "otpCode", rawOtp
                        ))))
                .andExpect(status().isTooManyRequests());

        // Kiểm tra mã hết hạn
        String sendResponse2 = mockMvc.perform(post("/api/v1/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "identifier", customer.getEmail(),
                                "purpose", "PHONE_VERIFICATION"
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String expiredChallengeId = objectMapper.readTree(sendResponse2).path("data").path("challengeId").asText();
        String rawOtp2 = DISPATCHED_OTPS.get(customer.getPhoneNumber() + ":PHONE_VERIFICATION");

        OtpChallenge challenge2 = otpChallengeRepository.findByChallengeCode(expiredChallengeId).orElseThrow();
        challenge2.setExpiresAt(LocalDateTime.now().minusSeconds(5));
        otpChallengeRepository.saveAndFlush(challenge2);

        mockMvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", expiredChallengeId,
                                "otpCode", rawOtp2
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("hết hạn")));
    }

    @Test
    @DisplayName("S3-01.2: Đặt lại mật khẩu qua OTP cập nhật BCrypt hash và thu hồi hiệu lực token cũ")
    void passwordResetUpdatesBcryptHashAndInvalidatesPreviousTokens() throws Exception {
        // 1. Đăng nhập lấy access token và refresh token cũ
        String loginRes = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", customer.getEmail(),
                                "password", "InitialPass123!"
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode oldSession = objectMapper.readTree(loginRes).path("data");
        String oldAccessToken = oldSession.path("token").asText();
        String oldRefreshToken = oldSession.path("refreshToken").asText();

        // 2. Yêu cầu gửi OTP PASSWORD_RESET
        String sendRes = mockMvc.perform(post("/api/v1/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "identifier", customer.getEmail(),
                                "phoneNumber", customer.getPhoneNumber(),
                                "purpose", "PASSWORD_RESET"
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String challengeId = objectMapper.readTree(sendRes).path("data").path("challengeId").asText();
        String rawOtp = DISPATCHED_OTPS.get(customer.getPhoneNumber() + ":PASSWORD_RESET");

        // 3. Đặt lại mật khẩu mới
        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId,
                                "otpCode", rawOtp,
                                "newPassword", "ResetPass2026!"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true));

        // 4. Mật khẩu cũ không còn đăng nhập được
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", customer.getEmail(),
                                "password", "InitialPass123!"
                        ))))
                .andExpect(status().isUnauthorized());

        // 5. Token cũ (access & refresh) bị vô hiệu hóa ngay lập tức
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + oldAccessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", oldRefreshToken))))
                .andExpect(status().isUnauthorized());

        // 6. Đăng nhập thành công bằng mật khẩu mới
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", customer.getEmail(),
                                "password", "ResetPass2026!"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }
}

