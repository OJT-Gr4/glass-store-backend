package com.glassstore.identity.service.impl;

import com.glassstore.identity.dto.*;
import com.glassstore.identity.entity.CustomerInteraction;
import com.glassstore.identity.entity.Role;
import com.glassstore.identity.entity.User;
import com.glassstore.identity.exception.EmailAlreadyExistsException;
import com.glassstore.identity.repository.CustomerInteractionRepository;
import com.glassstore.identity.repository.RoleRepository;
import com.glassstore.identity.repository.UserRepository;
import com.glassstore.identity.security.BruteForceService;
import com.glassstore.identity.security.JwtTokenProvider;
import com.glassstore.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    public static final String DEFAULT_AVATAR_URL = "https://i.pinimg.com/236x/3e/f1/fc/3ef1fcc3b6cbb97199f961bb0b3ead3f.jpg";
    private static final int MAX_URL_LENGTH = 1000;
    private static final int MAX_DATA_URI_LENGTH = 600_000;
    private static final Set<String> SAFE_DATA_IMAGE_PREFIXES = Set.of(
            "data:image/png;base64,",
            "data:image/jpeg;base64,",
            "data:image/jpg;base64,",
            "data:image/webp;base64,",
            "data:image/gif;base64,"
    );

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CustomerInteractionRepository interactionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final BruteForceService bruteForceService;

    @Override
    public List<UserDTO> getAllStaff() {
        return userRepository.findAllStaff().stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserDTO> getAllCustomers() {
        return userRepository.findAllCustomers().stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public UserDTO getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy người dùng với ID: " + id));
        return mapToDTO(user);
    }

    @Override
    @Transactional
    public UserDTO createStaff(CreateStaffRequest request) {
        if (request.getRoleName() != null && !request.getRoleName().isBlank() && !java.util.Set.of("ROLE_ADMIN","ROLE_SALES_STAFF","ROLE_OPTOMETRIST","ROLE_LAB_TECH").contains(request.getRoleName()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Unsupported staff role");
        if (request.getEmail() != null && userRepository.findByEmail(request.getEmail().trim().toLowerCase()).isPresent()) {
            throw new EmailAlreadyExistsException("Email '" + request.getEmail().trim() + "' đã được đăng ký trong hệ thống!");
        }

        String baseEmail = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : "user" + (new Random().nextInt(900) + 100) + "@optivision.vn";
        String username = baseEmail.split("@")[0].toLowerCase().replaceAll("[^a-z0-9]", "");
        if (userRepository.findByUsername(username).isPresent()) {
            username += "_" + (new Random().nextInt(900) + 100);
        }

        String roleName = request.getRoleName();
        if (roleName == null || roleName.trim().isEmpty()) {
            roleName = "ROLE_SALES_STAFF";
        }
        final String targetRole = roleName;
        Role role = roleRepository.findByName(targetRole)
                .orElseGet(() -> roleRepository.findByName("ROLE_SALES_STAFF")
                        .orElseThrow(() -> new RuntimeException("Role không tồn tại")));

        String pass = (request.getPassword() != null && !request.getPassword().trim().isEmpty())
                ? request.getPassword().trim()
                : "123456";

        String userStatus = (request.getStatus() != null && !request.getStatus().trim().isEmpty())
                ? request.getStatus().trim().toUpperCase()
                : "ACTIVE";

        User user = User.builder()
                .username(username)
                .email(baseEmail)
                .passwordHash(passwordEncoder.encode(pass))
                .fullName(request.getFullName() != null ? request.getFullName() : "Nhân viên mới")
                .phoneNumber(request.getPhoneNumber() != null ? request.getPhoneNumber() : "0901000000")
                .employeeCode(request.getEmployeeCode() != null && !request.getEmployeeCode().trim().isEmpty()
                        ? request.getEmployeeCode()
                        : "STF-" + (new Random().nextInt(900) + 100))
                .branchName(request.getBranchName() != null ? request.getBranchName() : "Flagship Q.1 (TP.HCM)")
                .avatarUrl(validateAndResolveAvatar(request.getAvatarUrl()))
                .status(userStatus)
                .roles(new HashSet<>(Collections.singletonList(role)))
                .build();

        return mapToDTO(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserDTO createCustomer(CreateCustomerRequest request) {
        String email = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : null;
        if (email != null && !email.isEmpty() && userRepository.findByEmail(email).isPresent()) {
            throw new EmailAlreadyExistsException("Email '" + email + "' đã tồn tại trong hệ thống!");
        }

        String username = "cus_" + (request.getPhoneNumber() != null ? request.getPhoneNumber() : UUID.randomUUID().toString().substring(0, 8));
        if (email == null || email.isEmpty()) {
            email = username + "@customer.optivision.vn";
        }

        Role customerRole = roleRepository.findByName("ROLE_CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_CUSTOMER").description("Khách hàng").build()));

        boolean consent = Boolean.TRUE.equals(request.getMarketingConsent());
        LocalDateTime consentTime = consent ? LocalDateTime.now() : null;

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode("Password2026@"))
                .fullName(request.getFullName() != null ? request.getFullName() : "Khách Hàng")
                .phoneNumber(request.getPhoneNumber())
                .branchName(request.getAddress())
                .address(request.getAddress())
                .gender(request.getGender())
                .avatarUrl(validateAndResolveAvatar(request.getAvatarUrl()))
                .marketingConsent(consent)
                .marketingConsentUpdatedAt(consentTime)
                .status("ACTIVE")
                .roles(new HashSet<>(Collections.singletonList(customerRole)))
                .build();

        return mapToDTO(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserDTO updateCustomer(Long id, UpdateCustomerRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy khách hàng với ID: " + id));

        boolean isCustomer = user.getRoles().stream().anyMatch(r -> "ROLE_CUSTOMER".equals(r.getName()));
        if (!isCustomer) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Tài khoản không phải hồ sơ khách hàng");
        }

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
        }
        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
            user.setPhoneNumber(request.getPhoneNumber().trim());
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            String newEmail = request.getEmail().trim().toLowerCase();
            if (!newEmail.equalsIgnoreCase(user.getEmail())) {
                if (userRepository.findByEmail(newEmail).isPresent()) {
                    throw new EmailAlreadyExistsException("Email '" + newEmail + "' đã tồn tại trên hệ thống!");
                }
                user.setEmail(newEmail);
            }
        }
        if (request.getAddress() != null) {
            user.setAddress(request.getAddress().trim());
            user.setBranchName(request.getAddress().trim());
        }
        if (request.getGender() != null) {
            user.setGender(request.getGender().trim());
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(validateAndResolveAvatar(request.getAvatarUrl()));
        }
        if (request.getMarketingConsent() != null) {
            boolean newConsent = request.getMarketingConsent();
            if (user.isMarketingConsent() != newConsent) {
                user.setMarketingConsent(newConsent);
                user.setMarketingConsentUpdatedAt(LocalDateTime.now());
                log.info("Customer {} marketing consent updated to {}", user.getId(), newConsent);
            }
        }

        return mapToDTO(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserDTO updateUserAvatar(Long id, String avatarUrl) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy người dùng với ID: " + id));
        user.setAvatarUrl(validateAndResolveAvatar(avatarUrl));
        return mapToDTO(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserDTO updateStaff(Long id, UpdateStaffRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy nhân viên với ID: " + id));
        boolean isStaff = user.getRoles().stream().anyMatch(r -> !"ROLE_CUSTOMER".equals(r.getName()));
        if (!isStaff) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Tài khoản không phải hồ sơ nhân viên");
        }
        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
        }
        if (request.getPhoneNumber() != null && !request.getPhoneNumber().isBlank()) {
            user.setPhoneNumber(request.getPhoneNumber().trim());
        }
        if (request.getBranchName() != null && !request.getBranchName().isBlank()) {
            user.setBranchName(request.getBranchName().trim());
        }
        if (request.getRoleName() != null && !request.getRoleName().isBlank()) {
            String roleName = request.getRoleName().trim();
            if (!Set.of("ROLE_ADMIN", "ROLE_SALES_STAFF", "ROLE_OPTOMETRIST", "ROLE_LAB_TECH").contains(roleName)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported staff role");
            }
            Role role = roleRepository.findByName(roleName)
                    .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST, "Role không tồn tại"));
            user.setRoles(new HashSet<>(Collections.singletonList(role)));
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(validateAndResolveAvatar(request.getAvatarUrl()));
        }
        return mapToDTO(userRepository.save(user));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDTO> searchCustomers(String query) {
        if (query == null || query.isBlank()) {
            return getAllCustomers();
        }
        return userRepository.searchCustomers(query.trim()).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerInteractionDTO> getCustomerInteractions(Long customerId) {
        if (!userRepository.existsById(customerId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy khách hàng: " + customerId);
        }
        return interactionRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(i -> CustomerInteractionDTO.builder()
                        .id(i.getId())
                        .customerId(i.getCustomerId())
                        .staffId(i.getStaffId())
                        .staffName(i.getStaffName())
                        .channel(i.getChannel())
                        .content(i.getContent())
                        .createdAt(i.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public CustomerInteractionDTO addCustomerInteraction(Long customerId, Long staffId, String staffName, CreateInteractionRequest request) {
        if (!userRepository.existsById(customerId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy khách hàng: " + customerId);
        }
        if (request == null || request.getContent() == null || request.getContent().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Nội dung ghi chú chăm sóc không được để trống");
        }

        String channel = (request.getChannel() != null && !request.getChannel().isBlank())
                ? request.getChannel().trim().toUpperCase()
                : "DIRECT";

        CustomerInteraction saved = interactionRepository.save(CustomerInteraction.builder()
                .customerId(customerId)
                .staffId(staffId)
                .staffName(staffName != null && !staffName.isBlank() ? staffName : "Nhân viên chăm sóc")
                .channel(channel)
                .content(request.getContent().trim())
                .build());

        log.info("Added interaction {} for customer {} via channel {}", saved.getId(), customerId, channel);

        return CustomerInteractionDTO.builder()
                .id(saved.getId())
                .customerId(saved.getCustomerId())
                .staffId(saved.getStaffId())
                .staffName(saved.getStaffName())
                .channel(saved.getChannel())
                .content(saved.getContent())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Override
    @Transactional
    public UserDTO registerCustomer(CustomerRegisterRequest request) {
        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new IllegalArgumentException("Email không được để trống!");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("Mật khẩu phải từ 6 ký tự trở lên!");
        }

        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.findByEmail(email).isPresent()) {
            throw new EmailAlreadyExistsException("Email '" + email + "' đã được đăng ký trong hệ thống!");
        }

        String baseUsername = email.split("@")[0].toLowerCase().replaceAll("[^a-z0-9]", "");
        String username = baseUsername;
        if (userRepository.findByUsername(username).isPresent()) {
            username += "_" + (new Random().nextInt(900) + 100);
        }

        Role customerRole = roleRepository.findByName("ROLE_CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_CUSTOMER").description("Khách hàng").build()));

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName() != null ? request.getFullName().trim() : "Khách Hàng")
                .phoneNumber(request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null)
                .avatarUrl(DEFAULT_AVATAR_URL)
                .status("ACTIVE")
                .roles(new HashSet<>(Collections.singletonList(customerRole)))
                .build();

        return mapToDTO(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserDTO toggleUserStatus(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng với ID: " + id));

        if ("ACTIVE".equalsIgnoreCase(user.getStatus())) {
            user.setStatus("INACTIVE");
        } else {
            user.setStatus("ACTIVE");
            user.setFailedAttempts(0);
            user.setLockoutExpiration(null);
        }

        return mapToDTO(userRepository.save(user));
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new RuntimeException("Không tìm thấy người dùng với ID: " + id);
        }
        userRepository.deleteById(id);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String identifier = request != null ? request.getEffectiveIdentifier() : "";
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new BadCredentialsException("Email hoặc mật khẩu không chính xác!");
        }

        identifier = identifier.trim();
        final String searchKey = identifier;
        final String normPhone = OtpServiceImpl.normalizePhone(searchKey);
        User user = userRepository.findByEmail(searchKey.toLowerCase())
                .or(() -> userRepository.findByUsername(searchKey))
                .or(() -> userRepository.findByEmployeeCode(searchKey))
                .or(() -> userRepository.findByEmployeeCode(searchKey.toUpperCase()))
                .or(() -> userRepository.findFirstByPhoneNumber(searchKey))
                .or(() -> (normPhone != null && !normPhone.isBlank())
                        ? userRepository.findFirstByPhoneNumber(normPhone)
                        : Optional.empty())
                .orElseThrow(() -> new BadCredentialsException("Email hoặc mật khẩu không chính xác!"));

        // 1. Kiểm tra khóa tài khoản (Brute Force Protection)
        bruteForceService.checkIfLocked(user);

        // 2. Xác thực mật khẩu với BCrypt
        String inputPassword = request.getPassword() != null ? request.getPassword() : "";
        boolean matches = passwordEncoder.matches(inputPassword, user.getPasswordHash());

        if (!matches) {
            bruteForceService.recordFailedAttempt(user);
            throw new BadCredentialsException("Email hoặc mật khẩu không chính xác!");
        }

        // 3. Đăng nhập thành công -> Reset số lần thử sai
        bruteForceService.recordSuccess(user);

        // 4. Kiểm tra trạng thái tài khoản
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new com.glassstore.identity.exception.AccountLockedException("Tài khoản này hiện đang bị tạm khóa hoặc ngừng hoạt động!");
        }

        // 5. Cấp phát JWT Access Token (24h) và Refresh Token (7 ngày)
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        Set<String> roleNames = user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
        String primaryRole = "ROLE_CUSTOMER";
        String defaultRoute = "/";

        if (roleNames.contains("ROLE_ADMIN")) {
            primaryRole = "ROLE_ADMIN";
            defaultRoute = "/dashboard";
        } else if (roleNames.contains("ROLE_OPTOMETRIST")) {
            primaryRole = "ROLE_OPTOMETRIST";
            defaultRoute = "/optometry-appointments";
        } else if (roleNames.contains("ROLE_LAB_TECH")) {
            primaryRole = "ROLE_LAB_TECH";
            defaultRoute = "/lab";
        } else if (roleNames.contains("ROLE_SALES_STAFF")) {
            primaryRole = "ROLE_SALES_STAFF";
            defaultRoute = "/pos";
        }

        return LoginResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(86400) // 24 giờ
                .id(user.getId())
                .userId(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .phoneVerified(user.isPhoneVerified())
                .employeeCode(user.getEmployeeCode())
                .branchName(user.getBranchName())
                .avatarUrl(effectiveAvatar(user.getAvatarUrl()))
                .roles(roleNames)
                .primaryRole(primaryRole)
                .defaultRoute(defaultRoute)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public LoginResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BadCredentialsException("Refresh token không hợp lệ hoặc đã hết hạn!");
        }

        String tokenType = jwtTokenProvider.getTokenType(refreshToken);
        if (!"REFRESH".equals(tokenType)) {
            throw new BadCredentialsException("Token cung cấp không phải là refresh token!");
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Người dùng không tồn tại!"));

        Integer tokenVersion = jwtTokenProvider.getClaimsFromToken(refreshToken).get("tokenVersion", Integer.class);
        if (tokenVersion != null && tokenVersion != user.getTokenVersion()) {
            throw new BadCredentialsException("Phiên đăng nhập đã hết hiệu lực do mật khẩu đã thay đổi!");
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new com.glassstore.identity.exception.AccountLockedException("Tài khoản đang bị tạm khóa!");
        }

        String newAccessToken = jwtTokenProvider.generateAccessToken(user);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user);

        Set<String> roleNames = user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());

        return LoginResponse.builder()
                .token(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(86400)
                .id(user.getId())
                .userId(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .avatarUrl(effectiveAvatar(user.getAvatarUrl()))
                .roles(roleNames)
                .primaryRole(roleNames.contains("ROLE_ADMIN") ? "ROLE_ADMIN" : "ROLE_CUSTOMER")
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy thông tin người dùng với ID: " + userId));
        return mapToDTO(user);
    }

    private UserDTO mapToDTO(User user) {
        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .phoneVerified(user.isPhoneVerified())
                .employeeCode(user.getEmployeeCode())
                .branchName(user.getBranchName())
                .address(user.getAddress() != null ? user.getAddress() : user.getBranchName())
                .gender(user.getGender())
                .avatarUrl(effectiveAvatar(user.getAvatarUrl()))
                .marketingConsent(user.isMarketingConsent())
                .marketingConsentUpdatedAt(user.getMarketingConsentUpdatedAt())
                .status(user.getStatus())
                .roles(user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()))
                .createdAt(user.getCreatedAt())
                .build();
    }

    private String effectiveAvatar(String stored) {
        return (stored != null && !stored.isBlank()) ? stored : DEFAULT_AVATAR_URL;
    }

    private String validateAndResolveAvatar(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return DEFAULT_AVATAR_URL;
        }
        String trimmed = rawUrl.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:")
                || lower.startsWith("vbscript:")
                || lower.startsWith("file:")
                || lower.contains("<script")
                || lower.contains("</script")
                || lower.contains("onerror=")
                || lower.contains("onload=")
                || lower.startsWith("data:image/svg")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Ảnh đại diện chứa định dạng hoặc mã lệnh không an toàn.");
        }
        if (lower.startsWith("data:image/")) {
            boolean safePrefix = SAFE_DATA_IMAGE_PREFIXES.stream().anyMatch(lower::startsWith);
            if (!safePrefix) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Chỉ hỗ trợ tải lên ảnh PNG, JPG, JPEG, WEBP hoặc GIF.");
            }
            if (trimmed.length() > MAX_DATA_URI_LENGTH) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Dung lượng ảnh tải lên quá lớn (tối đa ~450KB sau nén).");
            }
            return trimmed;
        }
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "URL ảnh đại diện không được vượt quá 1000 ký tự.");
        }
        try {
            java.net.URI uri = java.net.URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "URL ảnh đại diện phải bắt đầu bằng http:// hoặc https://.");
            }
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "URL ảnh đại diện không đúng định dạng.");
        }
        return trimmed;
    }
}
