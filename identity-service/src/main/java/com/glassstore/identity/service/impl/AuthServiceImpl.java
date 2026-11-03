package com.glassstore.identity.service.impl;

import com.glassstore.identity.dto.*;
import com.glassstore.identity.entity.*;
import com.glassstore.identity.repository.OtpVerificationRepository;
import com.glassstore.identity.repository.RefreshTokenRepository;
import com.glassstore.identity.repository.RoleRepository;
import com.glassstore.identity.repository.UserRepository;
import com.glassstore.identity.security.CustomUserDetails;
import com.glassstore.identity.security.JwtUtils;
import com.glassstore.identity.service.AuthService;
import com.glassstore.identity.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OtpVerificationRepository otpRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final EmailService emailService;

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_TIME_DURATION_MINUTES = 15;
    private static final int OTP_EXPIRY_MINUTES = 5;

    @Value("${jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs; // 7 days in ms

    @Override
    @Transactional
    public String register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        // 1. Check if email already registered and active
        var existingUserOpt = userRepository.findByEmail(email);
        if (existingUserOpt.isPresent()) {
            User existing = existingUserOpt.get();
            if (existing.getStatus() == UserStatus.ACTIVE) {
                throw new IllegalArgumentException("Email " + email + " đã được đăng ký và kích hoạt trong hệ thống.");
            }
            // If user is PENDING, we update password & resend OTP
            existing.setPassword(passwordEncoder.encode(request.getPassword()));
            existing.setFullName(request.getFullName());
            existing.setPhoneNumber(request.getPhoneNumber());
            userRepository.save(existing);
        } else {
            // 2. Fetch default role ROLE_CUSTOMER
            Role customerRole = roleRepository.findByName(RoleType.ROLE_CUSTOMER.name())
                    .orElseGet(() -> roleRepository
                            .save(new Role(RoleType.ROLE_CUSTOMER.name(), "Khách hàng mua sắm online")));

            Set<Role> roles = new HashSet<>();
            roles.add(customerRole);

            User user = User.builder()
                    .username(email)
                    .email(email)
                    .password(passwordEncoder.encode(request.getPassword()))
                    .fullName(request.getFullName())
                    .phoneNumber(request.getPhoneNumber())
                    .status(UserStatus.PENDING)
                    .failedLoginAttempts(0)
                    .roles(roles)
                    .build();

            userRepository.save(user);
        }

        // 3. Generate 6-digit OTP
        String otpCode = generateSecureOtp();

        // 4. Save OTP record
        OtpVerification otp = OtpVerification.builder()
                .email(email)
                .otpCode(otpCode)
                .expiryTime(LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES))
                .used(false)
                .build();
        otpRepository.save(otp);

        // 5. Send OTP email
        emailService.sendOtpEmail(email, otpCode);

        return "Đăng ký tài khoản thành công. Vui lòng kiểm tra email để nhập mã xác thực OTP 6 chữ số.";
    }

    @Override
    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        String otpCode = request.getOtp().trim();

        // 1. Find user
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản với email: " + email));

        // 2. Find OTP
        OtpVerification otpRecord = otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(email)
                .orElseThrow(() -> new IllegalArgumentException("Mã OTP không hợp lệ hoặc đã được sử dụng."));

        if (!otpRecord.getOtpCode().equals(otpCode)) {
            throw new IllegalArgumentException("Mã xác thực OTP không chính xác. Vui lòng kiểm tra lại.");
        }

        if (otpRecord.isExpired()) {
            throw new IllegalArgumentException(
                    "Mã OTP đã hết thời hạn hiệu lực (5 phút). Vui lòng yêu cầu gửi lại OTP mới.");
        }

        // 3. Mark OTP as used
        otpRecord.setUsed(true);
        otpRepository.save(otpRecord);

        // 4. Activate user
        user.setStatus(UserStatus.ACTIVE);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // 5. Auto login & generate tokens
        return createAuthResponse(user);
    }

    @Override
    @Transactional
    public String resendOtp(ResendOtpRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản với email: " + email));

        if (user.getStatus() == UserStatus.ACTIVE) {
            return "Tài khoản của bạn đã được kích hoạt trước đó. Vui lòng tiến hành đăng nhập.";
        }

        String otpCode = generateSecureOtp();
        OtpVerification otp = OtpVerification.builder()
                .email(email)
                .otpCode(otpCode)
                .expiryTime(LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES))
                .used(false)
                .build();
        otpRepository.save(otp);

        emailService.sendOtpEmail(email, otpCode);
        return "Mã OTP mới đã được gửi tới email " + email + ". Hiệu lực trong 5 phút.";
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        String rawPassword = request.getPassword();

        // 1. Find user by email
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Email hoặc mật khẩu không chính xác."));

        // 2. Check pending activation
        if (user.getStatus() == UserStatus.PENDING) {
            throw new IllegalStateException(
                    "Tài khoản chưa được kích hoạt. Vui lòng xác thực mã OTP gửi về email trước khi đăng nhập.");
        }

        // 3. Check brute force locking status
        if (user.getStatus() == UserStatus.LOCKED) {
            if (user.getLockedUntil() != null && LocalDateTime.now().isBefore(user.getLockedUntil())) {
                throw new LockedException(
                        "Tài khoản của bạn đang bị khóa do nhập sai mật khẩu 5 lần liên tiếp. Vui lòng thử lại sau 15 phút.");
            } else if (user.getLockedUntil() != null && LocalDateTime.now().isAfter(user.getLockedUntil())) {
                // Auto unlock
                user.setStatus(UserStatus.ACTIVE);
                user.setFailedLoginAttempts(0);
                user.setLockedUntil(null);
                userRepository.save(user);
            }
        }

        // 4. Verify password
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            int currentAttempts = (user.getFailedLoginAttempts() != null ? user.getFailedLoginAttempts() : 0) + 1;
            user.setFailedLoginAttempts(currentAttempts);

            if (currentAttempts >= MAX_FAILED_ATTEMPTS) {
                user.setStatus(UserStatus.LOCKED);
                user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_TIME_DURATION_MINUTES));
                userRepository.save(user);
                throw new LockedException("Bạn đã nhập sai mật khẩu " + MAX_FAILED_ATTEMPTS
                        + " lần liên tiếp. Tài khoản bị tạm khóa trong " + LOCK_TIME_DURATION_MINUTES + " phút.");
            } else {
                userRepository.save(user);
                int remaining = MAX_FAILED_ATTEMPTS - currentAttempts;
                throw new BadCredentialsException(
                        "Email hoặc mật khẩu không chính xác. Bạn còn " + remaining + " lần thử.");
            }
        }

        // 5. Login success -> reset failed attempts and unlock timer
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        return createAuthResponse(user);
    }

    @Override
    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String requestRefreshToken = request.getRefreshToken();

        RefreshToken refreshToken = refreshTokenRepository.findByToken(requestRefreshToken)
                .orElseThrow(() -> new IllegalArgumentException("Refresh token không tồn tại trong hệ thống."));

        if (refreshToken.isRevoked()) {
            throw new IllegalArgumentException("Refresh token đã bị thu hồi. Vui lòng đăng nhập lại.");
        }

        if (refreshToken.isExpired()) {
            refreshTokenRepository.delete(refreshToken);
            throw new IllegalArgumentException("Refresh token đã hết hạn sử dụng. Vui lòng đăng nhập lại.");
        }

        User user = refreshToken.getUser();
        CustomUserDetails userDetails = new CustomUserDetails(user);
        String newAccessToken = jwtUtils.generateAccessToken(userDetails);

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(requestRefreshToken)
                .tokenType("Bearer")
                .expiresIn(86400L)
                .user(mapToUserDto(user))
                .build();
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenRepository.findByToken(refreshToken).ifPresent(token -> {
                token.setRevoked(true);
                refreshTokenRepository.save(token);
            });
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto getCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng với email: " + email));
        return mapToUserDto(user);
    }

    private AuthResponse createAuthResponse(User user) {
        CustomUserDetails userDetails = new CustomUserDetails(user);
        String accessToken = jwtUtils.generateAccessToken(userDetails);

        // Generate persistent RefreshToken
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiryDate(Instant.now().plusMillis(refreshExpirationMs))
                .revoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken.getToken())
                .tokenType("Bearer")
                .expiresIn(86400L)
                .user(mapToUserDto(user))
                .build();
    }

    private UserDto mapToUserDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .status(user.getStatus())
                .roles(user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()))
                .build();
    }

    private String generateSecureOtp() {
        SecureRandom random = new SecureRandom();
        int otp = 100000 + random.nextInt(900000);
        return String.valueOf(otp);
    }
}
