package com.glassstore.identity.service;

import com.glassstore.identity.dto.*;
import com.glassstore.identity.entity.*;
import com.glassstore.identity.repository.OtpVerificationRepository;
import com.glassstore.identity.repository.RefreshTokenRepository;
import com.glassstore.identity.repository.RoleRepository;
import com.glassstore.identity.repository.UserRepository;
import com.glassstore.identity.security.CustomUserDetails;
import com.glassstore.identity.security.JwtUtils;
import com.glassstore.identity.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

        @Mock
        private UserRepository userRepository;

        @Mock
        private RoleRepository roleRepository;

        @Mock
        private OtpVerificationRepository otpRepository;

        @Mock
        private RefreshTokenRepository refreshTokenRepository;

        @Mock
        private PasswordEncoder passwordEncoder;

        @Mock
        private JwtUtils jwtUtils;

        @Mock
        private EmailService emailService;

        @InjectMocks
        private AuthServiceImpl authService;

        private Role customerRole;
        private User activeUser;

        @BeforeEach
        void setUp() {
                ReflectionTestUtils.setField(authService, "refreshExpirationMs", 604800000L);

                customerRole = Role.builder()
                                .id(5L)
                                .name("ROLE_CUSTOMER")
                                .description("Customer")
                                .build();

                Set<Role> roles = new HashSet<>();
                roles.add(customerRole);

                activeUser = User.builder()
                                .id(1L)
                                .username("test@gmail.com")
                                .email("test@gmail.com")
                                .password("$2a$10$encodedPassword")
                                .fullName("Nguyễn Khách Hàng")
                                .phoneNumber("0912345678")
                                .status(UserStatus.ACTIVE)
                                .failedLoginAttempts(0)
                                .roles(roles)
                                .build();
        }

        @Test
        @DisplayName("1. Đăng ký email hợp lệ -> Tạo user PENDING, sinh OTP và gửi mail thành công")
        void testRegister_Success() {
                RegisterRequest request = RegisterRequest.builder()
                                .email("newuser@gmail.com")
                                .password("Password123")
                                .fullName("Trần Khách Mới")
                                .phoneNumber("0988888888")
                                .build();

                when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
                when(roleRepository.findByName("ROLE_CUSTOMER")).thenReturn(Optional.of(customerRole));
                when(passwordEncoder.encode(anyString())).thenReturn("hashed_password");
                when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

                String result = authService.register(request);

                assertNotNull(result);
                assertTrue(result.contains("thành công"));
                verify(otpRepository, times(1)).save(any(OtpVerification.class));
                verify(emailService, times(1)).sendOtpEmail(eq("newuser@gmail.com"), anyString());
        }

        @Test
        @DisplayName("2. Đăng ký email trùng đã kích hoạt ACTIVE -> Ném lỗi IllegalArgumentException")
        void testRegister_DuplicateActiveEmail_ThrowsException() {
                RegisterRequest request = RegisterRequest.builder()
                                .email("test@gmail.com")
                                .password("Password123")
                                .fullName("Trùng Email")
                                .build();

                when(userRepository.findByEmail("test@gmail.com")).thenReturn(Optional.of(activeUser));

                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                () -> authService.register(request));
                assertTrue(ex.getMessage().contains("đã được đăng ký"));
                verify(otpRepository, never()).save(any());
        }

        @Test
        @DisplayName("3. Xác thực OTP đúng -> Kích hoạt tài khoản ACTIVE và cấp token")
        void testVerifyOtp_Success() {
                VerifyOtpRequest request = VerifyOtpRequest.builder()
                                .email("test@gmail.com")
                                .otp("123456")
                                .build();

                User pendingUser = User.builder()
                                .id(2L)
                                .email("test@gmail.com")
                                .password("hashed")
                                .fullName("Khách Đang Chờ")
                                .status(UserStatus.PENDING)
                                .roles(Set.of(customerRole))
                                .build();

                OtpVerification otp = OtpVerification.builder()
                                .id(1L)
                                .email("test@gmail.com")
                                .otpCode("123456")
                                .expiryTime(LocalDateTime.now().plusMinutes(5))
                                .used(false)
                                .build();

                when(userRepository.findByEmail("test@gmail.com")).thenReturn(Optional.of(pendingUser));
                when(otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("test@gmail.com"))
                                .thenReturn(Optional.of(otp));
                when(jwtUtils.generateAccessToken(any(CustomUserDetails.class))).thenReturn("valid_access_token");
                when(refreshTokenRepository.save(any(RefreshToken.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                AuthResponse response = authService.verifyOtp(request);

                assertNotNull(response);
                assertEquals("valid_access_token", response.getAccessToken());
                assertNotNull(response.getRefreshToken());
                assertEquals(UserStatus.ACTIVE, pendingUser.getStatus());
                assertTrue(otp.isUsed());
        }

        @Test
        @DisplayName("4. Xác thực OTP sai -> Ném lỗi IllegalArgumentException")
        void testVerifyOtp_IncorrectCode_ThrowsException() {
                VerifyOtpRequest request = VerifyOtpRequest.builder()
                                .email("test@gmail.com")
                                .otp("999999")
                                .build();

                OtpVerification otp = OtpVerification.builder()
                                .id(1L)
                                .email("test@gmail.com")
                                .otpCode("123456")
                                .expiryTime(LocalDateTime.now().plusMinutes(5))
                                .used(false)
                                .build();

                when(userRepository.findByEmail("test@gmail.com")).thenReturn(Optional.of(activeUser));
                when(otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("test@gmail.com"))
                                .thenReturn(Optional.of(otp));

                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                () -> authService.verifyOtp(request));
                assertTrue(ex.getMessage().contains("không chính xác"));
                assertFalse(otp.isUsed());
        }

        @Test
        @DisplayName("5. Xác thực OTP hết hạn sau 5 phút -> Ném lỗi IllegalArgumentException")
        void testVerifyOtp_Expired_ThrowsException() {
                VerifyOtpRequest request = VerifyOtpRequest.builder()
                                .email("test@gmail.com")
                                .otp("123456")
                                .build();

                OtpVerification expiredOtp = OtpVerification.builder()
                                .id(1L)
                                .email("test@gmail.com")
                                .otpCode("123456")
                                .expiryTime(LocalDateTime.now().minusMinutes(1)) // Expired
                                .used(false)
                                .build();

                when(userRepository.findByEmail("test@gmail.com")).thenReturn(Optional.of(activeUser));
                when(otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("test@gmail.com"))
                                .thenReturn(Optional.of(expiredOtp));

                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                () -> authService.verifyOtp(request));
                assertTrue(ex.getMessage().contains("hết thời hạn hiệu lực"));
        }

        @Test
        @DisplayName("6. Đăng nhập khi tài khoản PENDING -> Ném lỗi IllegalStateException")
        void testLogin_PendingAccount_ThrowsException() {
                LoginRequest request = LoginRequest.builder()
                                .email("pending@gmail.com")
                                .password("Password123")
                                .build();

                User pendingUser = User.builder()
                                .email("pending@gmail.com")
                                .status(UserStatus.PENDING)
                                .build();

                when(userRepository.findByEmail("pending@gmail.com")).thenReturn(Optional.of(pendingUser));

                IllegalStateException ex = assertThrows(IllegalStateException.class, () -> authService.login(request));
                assertTrue(ex.getMessage().contains("chưa được kích hoạt"));
        }

        @Test
        @DisplayName("7. Đăng nhập thành công với email và password đúng -> Reset counter & cấp token")
        void testLogin_Success() {
                LoginRequest request = LoginRequest.builder()
                                .email("test@gmail.com")
                                .password("correctPassword")
                                .build();

                when(userRepository.findByEmail("test@gmail.com")).thenReturn(Optional.of(activeUser));
                when(passwordEncoder.matches("correctPassword", activeUser.getPassword())).thenReturn(true);
                when(jwtUtils.generateAccessToken(any(CustomUserDetails.class))).thenReturn("access_token_abc");
                when(refreshTokenRepository.save(any(RefreshToken.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                AuthResponse response = authService.login(request);

                assertNotNull(response);
                assertEquals("access_token_abc", response.getAccessToken());
                assertEquals(0, activeUser.getFailedLoginAttempts());
                assertNull(activeUser.getLockedUntil());
        }

        @Test
        @DisplayName("8. Nhập sai mật khẩu lần 1 -> failedLoginAttempts = 1, thông báo còn 4 lần")
        void testLogin_FailedPassword_IncrementsCounter() {
                LoginRequest request = LoginRequest.builder()
                                .email("test@gmail.com")
                                .password("wrongPassword")
                                .build();

                activeUser.setFailedLoginAttempts(0);

                when(userRepository.findByEmail("test@gmail.com")).thenReturn(Optional.of(activeUser));
                when(passwordEncoder.matches("wrongPassword", activeUser.getPassword())).thenReturn(false);

                BadCredentialsException ex = assertThrows(BadCredentialsException.class,
                                () -> authService.login(request));
                assertTrue(ex.getMessage().contains("còn 4 lần thử"));
                assertEquals(1, activeUser.getFailedLoginAttempts());
                assertEquals(UserStatus.ACTIVE, activeUser.getStatus());
        }

        @Test
        @DisplayName("9. Nhập sai mật khẩu liên tiếp lần thứ 5 -> Khóa tài khoản đúng 15 phút")
        void testLogin_FailedPassword_FifthTime_LocksAccount15Minutes() {
                LoginRequest request = LoginRequest.builder()
                                .email("test@gmail.com")
                                .password("wrongPassword")
                                .build();

                activeUser.setFailedLoginAttempts(4); // Lần thứ 5 chuẩn bị diễn ra

                when(userRepository.findByEmail("test@gmail.com")).thenReturn(Optional.of(activeUser));
                when(passwordEncoder.matches("wrongPassword", activeUser.getPassword())).thenReturn(false);

                LockedException ex = assertThrows(LockedException.class, () -> authService.login(request));
                assertTrue(ex.getMessage().contains("tạm khóa trong 15 phút"));
                assertEquals(5, activeUser.getFailedLoginAttempts());
                assertEquals(UserStatus.LOCKED, activeUser.getStatus());
                assertNotNull(activeUser.getLockedUntil());
                assertTrue(activeUser.getLockedUntil().isAfter(LocalDateTime.now().plusMinutes(14)));
        }

        @Test
        @DisplayName("10. Thử đăng nhập lần 6 trong thời gian bị khóa -> Bị từ chối bằng LockedException")
        void testLogin_DuringLockPeriod_Rejected() {
                LoginRequest request = LoginRequest.builder()
                                .email("test@gmail.com")
                                .password("anyPassword")
                                .build();

                activeUser.setStatus(UserStatus.LOCKED);
                activeUser.setLockedUntil(LocalDateTime.now().plusMinutes(10)); // Còn 10 phút khóa

                when(userRepository.findByEmail("test@gmail.com")).thenReturn(Optional.of(activeUser));

                LockedException ex = assertThrows(LockedException.class, () -> authService.login(request));
                assertTrue(ex.getMessage().contains("đang bị khóa"));
        }

        @Test
        @DisplayName("11. Đăng nhập sau khi hết 15 phút khóa -> Tự động mở khóa và đăng nhập bình thường")
        void testLogin_AfterLockPeriodExpired_AutoUnlocks() {
                LoginRequest request = LoginRequest.builder()
                                .email("test@gmail.com")
                                .password("correctPassword")
                                .build();

                activeUser.setStatus(UserStatus.LOCKED);
                activeUser.setLockedUntil(LocalDateTime.now().minusMinutes(1)); // Đã hết hạn khóa 1 phút trước

                when(userRepository.findByEmail("test@gmail.com")).thenReturn(Optional.of(activeUser));
                when(passwordEncoder.matches("correctPassword", activeUser.getPassword())).thenReturn(true);
                when(jwtUtils.generateAccessToken(any(CustomUserDetails.class))).thenReturn("new_token");
                when(refreshTokenRepository.save(any(RefreshToken.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                AuthResponse response = authService.login(request);

                assertNotNull(response);
                assertEquals(UserStatus.ACTIVE, activeUser.getStatus());
                assertEquals(0, activeUser.getFailedLoginAttempts());
                assertNull(activeUser.getLockedUntil());
        }

        @Test
        @DisplayName("12. RefreshToken hợp lệ -> Cấp AccessToken mới")
        void testRefreshToken_Valid_Success() {
                RefreshTokenRequest request = RefreshTokenRequest.builder()
                                .refreshToken("valid-uuid-refresh-token")
                                .build();

                RefreshToken refreshToken = RefreshToken.builder()
                                .id(1L)
                                .token("valid-uuid-refresh-token")
                                .user(activeUser)
                                .expiryDate(Instant.now().plusSeconds(3600))
                                .revoked(false)
                                .build();

                when(refreshTokenRepository.findByToken("valid-uuid-refresh-token"))
                                .thenReturn(Optional.of(refreshToken));
                when(jwtUtils.generateAccessToken(any(CustomUserDetails.class))).thenReturn("renewed_access_token");

                AuthResponse response = authService.refreshToken(request);

                assertNotNull(response);
                assertEquals("renewed_access_token", response.getAccessToken());
                assertEquals("valid-uuid-refresh-token", response.getRefreshToken());
        }

        @Test
        @DisplayName("13. RefreshToken bị thu hồi (revoked) -> Ném lỗi IllegalArgumentException")
        void testRefreshToken_Revoked_ThrowsException() {
                RefreshTokenRequest request = RefreshTokenRequest.builder()
                                .refreshToken("revoked-token")
                                .build();

                RefreshToken revokedToken = RefreshToken.builder()
                                .id(2L)
                                .token("revoked-token")
                                .user(activeUser)
                                .expiryDate(Instant.now().plusSeconds(3600))
                                .revoked(true)
                                .build();

                when(refreshTokenRepository.findByToken("revoked-token")).thenReturn(Optional.of(revokedToken));

                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                () -> authService.refreshToken(request));
                assertTrue(ex.getMessage().contains("thu hồi"));
        }

        @Test
        @DisplayName("14. Logout -> Thu hồi RefreshToken")
        void testLogout_RevokesToken() {
                RefreshToken refreshToken = RefreshToken.builder()
                                .id(1L)
                                .token("logout-token")
                                .revoked(false)
                                .build();

                when(refreshTokenRepository.findByToken("logout-token")).thenReturn(Optional.of(refreshToken));

                authService.logout("logout-token");

                assertTrue(refreshToken.isRevoked());
                verify(refreshTokenRepository, times(1)).save(refreshToken);
        }
}
