package com.glassstore.identity.service.impl;

import com.glassstore.identity.dto.*;
import com.glassstore.identity.entity.OtpChallenge;
import com.glassstore.identity.entity.User;
import com.glassstore.identity.repository.OtpChallengeRepository;
import com.glassstore.identity.repository.UserRepository;
import com.glassstore.identity.service.OtpService;
import com.glassstore.identity.service.SmsGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpServiceImpl implements OtpService {

    public static final String PURPOSE_PHONE_VERIFICATION = "PHONE_VERIFICATION";
    public static final String PURPOSE_PASSWORD_RESET = "PASSWORD_RESET";
    private static final Set<String> SUPPORTED_PURPOSES = Set.of(
            PURPOSE_PHONE_VERIFICATION,
            PURPOSE_PASSWORD_RESET
    );

    private final UserRepository userRepository;
    private final OtpChallengeRepository otpChallengeRepository;
    private final PasswordEncoder passwordEncoder;
    private final SmsGateway smsGateway;
    private final com.glassstore.identity.service.EmailOtpSender emailOtpSender;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${otp.expiration-seconds:300}")
    private int otpExpirationSeconds = 300;

    @Value("${otp.max-attempts:5}")
    private int maxAttempts = 5;

    @Value("${otp.max-sends-per-window:5}")
    private int maxSendsPerWindow = 5;

    @Value("${otp.rate-limit-window-minutes:15}")
    private int rateLimitWindowMinutes = 15;

    @Override
    @Transactional
    public OtpSendResponse sendOtp(OtpSendRequest request) {
        if (request == null || request.getPurpose() == null || request.getPurpose().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mục đích gửi OTP không hợp lệ");
        }

        String purpose = request.getPurpose().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_PURPOSES.contains(purpose)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mục đích OTP không được hỗ trợ: " + purpose);
        }

        User user = resolveTargetUser(request);
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Tài khoản đang bị tạm khóa hoặc ngừng hoạt động");
        }

        String targetEmail = user.getEmail();
        if ((targetEmail == null || targetEmail.isBlank()) && request.getEmail() != null && !request.getEmail().isBlank()) {
            targetEmail = request.getEmail().trim();
        }

        boolean isPhoneVerification = PURPOSE_PHONE_VERIFICATION.equalsIgnoreCase(purpose);

        String normalizedUserPhone = normalizePhone(user.getPhoneNumber());
        if (normalizedUserPhone == null || normalizedUserPhone.isBlank()) {
            normalizedUserPhone = normalizePhone(request.getPhoneNumber());
        }

        if (isPhoneVerification && (normalizedUserPhone == null || normalizedUserPhone.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tài khoản chưa đăng ký số điện thoại nhận OTP");
        }

        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank() && normalizedUserPhone != null) {
            String requestedPhone = normalizePhone(request.getPhoneNumber());
            if (!normalizedUserPhone.equals(requestedPhone)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số điện thoại không khớp với tài khoản đã đăng ký");
            }
        }

        if (!isPhoneVerification && (targetEmail == null || targetEmail.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tài khoản chưa đăng ký email để nhận mã OTP");
        }

        LocalDateTime now = LocalDateTime.now();
        long sendsInWindow = otpChallengeRepository.countByUserIdAndPurposeAndCreatedAtAfter(
                user.getId(),
                purpose,
                now.minusMinutes(Math.max(1, rateLimitWindowMinutes))
        );
        if (sendsInWindow >= Math.max(1, maxSendsPerWindow)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã yêu cầu gửi OTP quá số lần cho phép. Vui lòng thử lại sau ít phút."
            );
        }

        // Invalidate previous active challenges for the same user and purpose
        List<OtpChallenge> activeChallenges = otpChallengeRepository
                .findByUserIdAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(user.getId(), purpose);
        for (OtpChallenge old : activeChallenges) {
            old.setInvalidatedAt(now);
        }
        if (!activeChallenges.isEmpty()) {
            otpChallengeRepository.saveAll(activeChallenges);
        }

        String rawOtp = String.format("%06d", secureRandom.nextInt(1_000_000));
        int ttlSeconds = Math.max(30, otpExpirationSeconds);
        LocalDateTime expiresAt = now.plusSeconds(ttlSeconds);

        // Send via external SMS gateway if phone available
        if (normalizedUserPhone != null && !normalizedUserPhone.isBlank()) {
            smsGateway.sendOtp(normalizedUserPhone, rawOtp, purpose, expiresAt);
        }

        // Send via Gmail SMTP Email OTP
        if (targetEmail != null && !targetEmail.isBlank()) {
            emailOtpSender.sendOtpEmail(targetEmail, user.getFullName(), rawOtp, purpose, expiresAt);
        }

        OtpChallenge challenge = new OtpChallenge();
        challenge.setChallengeCode(UUID.randomUUID().toString());
        challenge.setUserId(user.getId());
        challenge.setEmail(targetEmail);
        challenge.setPhoneNumber(normalizedUserPhone != null ? normalizedUserPhone : "");
        challenge.setPurpose(purpose);
        challenge.setCodeHash(passwordEncoder.encode(rawOtp));
        challenge.setExpiresAt(expiresAt);
        challenge.setAttemptCount(0);
        challenge.setMaxAttempts(Math.max(1, maxAttempts));
        otpChallengeRepository.save(challenge);

        String maskedEmail = maskEmail(targetEmail);
        String masked = maskPhone(normalizedUserPhone);
        String channel = (targetEmail != null && !targetEmail.isBlank()) ? "EMAIL" : "SMS";
        log.info("Đã khởi tạo phiên OTP [purpose={}, challengeId={}, userId={}, channel={}, email={}, phone={}]",
                purpose, challenge.getChallengeCode(), user.getId(), channel, maskedEmail, masked);

        return OtpSendResponse.builder()
                .challengeId(challenge.getChallengeCode())
                .purpose(purpose)
                .maskedEmail(maskedEmail)
                .maskedPhone(masked)
                .deliveryChannel(channel)
                .expiresInSeconds(ttlSeconds)
                .expiresAt(expiresAt)
                .maxAttempts(challenge.getMaxAttempts())
                .build();
    }

    @Override
    @Transactional(noRollbackFor = {ResponseStatusException.class, IllegalArgumentException.class})
    public OtpVerifyResponse verifyPhoneOtp(OtpVerifyRequest request) {
        if (request == null || request.getChallengeId() == null || request.getChallengeId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã phiên xác thực OTP không hợp lệ");
        }
        OtpChallenge challenge = validateAndConsumeOtp(
                request.getChallengeId().trim(),
                request.getOtpCode(),
                PURPOSE_PHONE_VERIFICATION
        );

        User user = userRepository.findById(challenge.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        user.setPhoneVerified(true);
        userRepository.save(user);

        return OtpVerifyResponse.builder()
                .verified(true)
                .userId(user.getId())
                .purpose(PURPOSE_PHONE_VERIFICATION)
                .maskedPhone(maskPhone(challenge.getPhoneNumber()))
                .maskedEmail(maskEmail(challenge.getEmail() != null ? challenge.getEmail() : user.getEmail()))
                .phoneVerified(true)
                .verifiedAt(challenge.getConsumedAt())
                .build();
    }

    @Override
    @Transactional(noRollbackFor = {ResponseStatusException.class, IllegalArgumentException.class})
    public OtpVerifyResponse resetPassword(PasswordResetRequest request) {
        if (request == null || request.getChallengeId() == null || request.getChallengeId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã phiên xác thực OTP không hợp lệ");
        }
        String newPassword = request.getNewPassword();
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 72
                || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu mới phải từ 6 đến 72 ký tự hợp lệ");
        }

        OtpChallenge challenge = validateAndConsumeOtp(
                request.getChallengeId().trim(),
                request.getOtpCode(),
                PURPOSE_PASSWORD_RESET
        );

        User user = userRepository.findById(challenge.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setFailedAttempts(0);
        user.setLockoutExpiration(null);
        user.setPhoneVerified(true);
        userRepository.save(user);

        log.info("Đặt lại mật khẩu thành công qua OTP cho userId={}, tokenVersion mới={}",
                user.getId(), user.getTokenVersion());

        return OtpVerifyResponse.builder()
                .verified(true)
                .userId(user.getId())
                .purpose(PURPOSE_PASSWORD_RESET)
                .maskedPhone(maskPhone(challenge.getPhoneNumber()))
                .maskedEmail(maskEmail(challenge.getEmail() != null ? challenge.getEmail() : user.getEmail()))
                .phoneVerified(user.isPhoneVerified())
                .verifiedAt(challenge.getConsumedAt())
                .build();
    }

    private OtpChallenge validateAndConsumeOtp(String challengeId, String otpCode, String expectedPurpose) {
        if (otpCode == null || !otpCode.trim().matches("^\\d{6}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP phải gồm đúng 6 chữ số");
        }

        OtpChallenge challenge = otpChallengeRepository.findByChallengeCode(challengeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên xác thực OTP"));

        if (!expectedPurpose.equalsIgnoreCase(challenge.getPurpose())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phiên xác thực OTP không khớp mục đích sử dụng");
        }

        if (challenge.getConsumedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP này đã được sử dụng");
        }

        if (challenge.getInvalidatedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP này đã bị hủy hiệu lực do có mã mới");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(challenge.getExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP đã hết hạn");
        }

        if (challenge.getAttemptCount() >= challenge.getMaxAttempts()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Mã OTP đã bị khóa do nhập sai quá số lần quy định");
        }

        boolean matched = passwordEncoder.matches(otpCode.trim(), challenge.getCodeHash());
        if (!matched) {
            challenge.setAttemptCount(challenge.getAttemptCount() + 1);
            otpChallengeRepository.saveAndFlush(challenge);
            if (challenge.getAttemptCount() >= challenge.getMaxAttempts()) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Bạn đã nhập sai OTP quá " + challenge.getMaxAttempts() + " lần. Phiên OTP đã bị khóa."
                );
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã OTP không chính xác");
        }

        challenge.setConsumedAt(now);
        return otpChallengeRepository.saveAndFlush(challenge);
    }

    private User resolveTargetUser(OtpSendRequest request) {
        String identifier = request.getEffectiveIdentifier();
        String phone = normalizePhone(request.getPhoneNumber());

        if (identifier != null && !identifier.isBlank()) {
            String normalizedId = identifier.trim();
            return userRepository.findByEmail(normalizedId.toLowerCase(Locale.ROOT))
                    .or(() -> userRepository.findByUsername(normalizedId))
                    .or(() -> userRepository.findByEmployeeCode(normalizedId))
                    .or(() -> userRepository.findByEmployeeCode(normalizedId.toUpperCase(Locale.ROOT)))
                    .or(() -> {
                        String normIdPhone = normalizePhone(normalizedId);
                        return normIdPhone != null ? userRepository.findFirstByPhoneNumber(normIdPhone) : java.util.Optional.empty();
                    })
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Không tìm thấy tài khoản phù hợp với thông tin xác thực"
                    ));
        }

        if (phone != null && !phone.isBlank()) {
            return userRepository.findFirstByPhoneNumber(phone)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Không tìm thấy tài khoản gắn với số điện thoại này"
                    ));
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng cung cấp email, tài khoản hoặc số điện thoại");
    }

    public static String normalizePhone(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replaceAll("[\\s\\-\\.()]", "");
        if (cleaned.startsWith("+84")) {
            cleaned = "0" + cleaned.substring(3);
        } else if (cleaned.startsWith("84") && cleaned.length() == 11) {
            cleaned = "0" + cleaned.substring(2);
        }
        return cleaned;
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        int visibleDigits = Math.min(4, phone.length());
        String tail = phone.substring(phone.length() - visibleDigits);
        return "*".repeat(Math.max(4, phone.length() - visibleDigits)) + tail;
    }

    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "****";
        }
        String[] parts = email.split("@", 2);
        String name = parts[0];
        String domain = parts[1];
        if (name.length() <= 2) {
            return name.charAt(0) + "***@" + domain;
        }
        int keepStart = Math.min(2, name.length());
        int keepEnd = Math.min(2, Math.max(0, name.length() - keepStart));
        String start = name.substring(0, keepStart);
        String end = keepEnd > 0 ? name.substring(name.length() - keepEnd) : "";
        return start + "***" + end + "@" + domain;
    }
}

