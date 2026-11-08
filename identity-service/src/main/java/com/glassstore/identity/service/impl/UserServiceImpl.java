package com.glassstore.identity.service.impl;

import com.glassstore.identity.dto.*;
import com.glassstore.identity.entity.Role;
import com.glassstore.identity.entity.User;
import com.glassstore.identity.exception.EmailAlreadyExistsException;
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

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
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
                .status(userStatus)
                .roles(new HashSet<>(Collections.singletonList(role)))
                .build();

        return mapToDTO(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserDTO createCustomer(CreateCustomerRequest request) {
        String email = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : null;
        if (email != null && userRepository.findByEmail(email).isPresent()) {
            throw new EmailAlreadyExistsException("Email '" + email + "' đã tồn tại trong hệ thống!");
        }

        String username = "cus_" + (request.getPhoneNumber() != null ? request.getPhoneNumber() : UUID.randomUUID().toString().substring(0, 8));
        if (email == null || email.isEmpty()) {
            email = username + "@customer.optivision.vn";
        }

        Role customerRole = roleRepository.findByName("ROLE_CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_CUSTOMER").description("Khách hàng").build()));

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode("Password2026@"))
                .fullName(request.getFullName() != null ? request.getFullName() : "Khách Hàng")
                .phoneNumber(request.getPhoneNumber())
                .branchName(request.getAddress())
                .status("ACTIVE")
                .roles(new HashSet<>(Collections.singletonList(customerRole)))
                .build();

        return mapToDTO(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserDTO registerCustomer(CustomerRegisterRequest request) {
        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new IllegalArgumentException("Email không được để trống!");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("Mật khẩu phải có độ dài tối thiểu 6 ký tự!");
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new EmailAlreadyExistsException("Email '" + normalizedEmail + "' đã được đăng ký trong hệ thống!");
        }

        String baseUsername = normalizedEmail.split("@")[0].replaceAll("[^a-z0-9]", "");
        if (baseUsername.isEmpty()) {
            baseUsername = "customer";
        }
        // Keep within the persisted 50-character username and avoid small random suffix collisions.
        baseUsername = baseUsername.substring(0, Math.min(36, baseUsername.length()));
        String finalUsername = baseUsername;
        while (userRepository.findByUsername(finalUsername).isPresent()) {
            finalUsername = baseUsername + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }

        Role customerRole = roleRepository.findByName("ROLE_CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_CUSTOMER").description("Khách hàng mua sắm").build()));

        User user = User.builder()
                .username(finalUsername)
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName() != null ? request.getFullName().trim() : "Khách Hàng")
                .phoneNumber(request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null)
                .status("ACTIVE") // Tài khoản có trạng thái ACTIVE ngay sau khi đăng ký thành công, không OTP
                .failedAttempts(0)
                .roles(new HashSet<>(Collections.singletonList(customerRole)))
                .build();

        User savedUser = userRepository.save(user);
        log.info("Đăng ký thành công tài khoản khách hàng ID: {}", savedUser.getId());
        return mapToDTO(savedUser);
    }

    @Override
    @Transactional
    public UserDTO toggleUserStatus(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng với ID: " + id));
        if ("ACTIVE".equalsIgnoreCase(user.getStatus())) {
            user.setStatus("LOCKED");
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
        userRepository.deleteById(id);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        // Lấy identifier từ request (hỗ trợ cả email và username/employeeCode)
        String identifier = request.getEffectiveIdentifier();
        if (identifier == null || identifier.isEmpty()) {
            throw new BadCredentialsException("Vui lòng cung cấp email hoặc tài khoản đăng nhập!");
        }

        // Tìm user: ưu tiên email (customer web), sau đó username và employeeCode (nhân viên nội bộ)
        User user = userRepository.findByEmail(identifier.toLowerCase())
                .or(() -> userRepository.findByUsername(identifier))
                .or(() -> userRepository.findByEmployeeCode(identifier))
                .or(() -> userRepository.findByEmployeeCode(identifier.toUpperCase()))
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
                .employeeCode(user.getEmployeeCode())
                .branchName(user.getBranchName())
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
                .employeeCode(user.getEmployeeCode())
                .branchName(user.getBranchName())
                .status(user.getStatus())
                .roles(user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()))
                .createdAt(user.getCreatedAt())
                .build();
    }
}
