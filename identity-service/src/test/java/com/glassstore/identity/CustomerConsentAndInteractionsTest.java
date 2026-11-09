package com.glassstore.identity;

import com.glassstore.identity.dto.*;
import com.glassstore.identity.entity.CustomerInteraction;
import com.glassstore.identity.entity.Role;
import com.glassstore.identity.entity.User;
import com.glassstore.identity.repository.CustomerInteractionRepository;
import com.glassstore.identity.repository.RoleRepository;
import com.glassstore.identity.repository.UserRepository;
import com.glassstore.identity.security.BruteForceService;
import com.glassstore.identity.security.JwtTokenProvider;
import com.glassstore.identity.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerConsentAndInteractionsTest {

    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private CustomerInteractionRepository interactionRepository;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private BruteForceService bruteForceService;

    private UserServiceImpl userService;

    private Role customerRole;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        interactionRepository = mock(CustomerInteractionRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        bruteForceService = mock(BruteForceService.class);

        customerRole = Role.builder().id(1L).name("ROLE_CUSTOMER").description("Khách hàng").build();
        when(roleRepository.findByName("ROLE_CUSTOMER")).thenReturn(Optional.of(customerRole));
        when(passwordEncoder.encode(any())).thenReturn("hashed-pwd");

        userService = new UserServiceImpl(
                userRepository, roleRepository, interactionRepository,
                passwordEncoder, jwtTokenProvider, bruteForceService
        );
    }

    @Test
    @DisplayName("S3-04: Mặc định tạo khách hàng không tự động opt-in marketing consent")
    void createCustomer_defaultNoConsent() {
        CreateCustomerRequest req = CreateCustomerRequest.builder()
                .fullName("Nguyen Van A")
                .phoneNumber("0912345678")
                .email("vana@example.com")
                .address("123 Le Loi, Q.1")
                .gender("MALE")
                .build();

        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDTO dto = userService.createCustomer(req);

        assertThat(dto.isMarketingConsent()).isFalse();
        assertThat(dto.getMarketingConsentUpdatedAt()).isNull();
        assertThat(dto.getAddress()).isEqualTo("123 Le Loi, Q.1");
        assertThat(dto.getGender()).isEqualTo("MALE");
    }

    @Test
    @DisplayName("S3-04: Cập nhật thông tin khách hàng và thay đổi consent lưu đúng timestamp")
    void updateCustomer_consentToggle_recordsTimestamp() {
        User existing = User.builder()
                .id(10L)
                .username("cus_10")
                .email("old@example.com")
                .fullName("Nguyen Van Cu")
                .phoneNumber("0900000001")
                .address("Old Address")
                .gender("FEMALE")
                .marketingConsent(false)
                .roles(Collections.singleton(customerRole))
                .build();

        when(userRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateCustomerRequest updateReq = UpdateCustomerRequest.builder()
                .fullName("Nguyen Van Moi")
                .address("456 Nguyen Trai, Q.5")
                .gender("MALE")
                .marketingConsent(true)
                .build();

        UserDTO updated = userService.updateCustomer(10L, updateReq);

        assertThat(updated.getFullName()).isEqualTo("Nguyen Van Moi");
        assertThat(updated.getAddress()).isEqualTo("456 Nguyen Trai, Q.5");
        assertThat(updated.getGender()).isEqualTo("MALE");
        assertThat(updated.isMarketingConsent()).isTrue();
        assertThat(updated.getMarketingConsentUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("S3-04: Tìm kiếm khách hàng theo tên, số điện thoại hoặc mã")
    void searchCustomers_queriesRepository() {
        User u1 = User.builder().id(1L).fullName("Tran Van B").phoneNumber("0988776655").roles(Collections.singleton(customerRole)).build();
        when(userRepository.searchCustomers("0988")).thenReturn(Collections.singletonList(u1));

        List<UserDTO> results = userService.searchCustomers("0988");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getFullName()).isEqualTo("Tran Van B");
    }

    @Test
    @DisplayName("S3-04: Thêm và lấy danh sách ghi chú chăm sóc (CustomerInteraction) thật")
    void customerInteractions_addAndRetrieve() {
        when(userRepository.existsById(50L)).thenReturn(true);

        CustomerInteraction saved = CustomerInteraction.builder()
                .id(100L)
                .customerId(50L)
                .staffId(2L)
                .staffName("CS Staff")
                .channel("ZALO")
                .content("Đã gọi điện nhắc khách tái khám định kỳ")
                .createdAt(LocalDateTime.now())
                .build();

        when(interactionRepository.save(any(CustomerInteraction.class))).thenReturn(saved);
        when(interactionRepository.findByCustomerIdOrderByCreatedAtDesc(50L)).thenReturn(Collections.singletonList(saved));

        CreateInteractionRequest addReq = CreateInteractionRequest.builder()
                .channel("ZALO")
                .content("Đã gọi điện nhắc khách tái khám định kỳ")
                .build();

        CustomerInteractionDTO added = userService.addCustomerInteraction(50L, 2L, "CS Staff", addReq);
        assertThat(added.getId()).isEqualTo(100L);
        assertThat(added.getChannel()).isEqualTo("ZALO");
        assertThat(added.getContent()).contains("nhắc khách tái khám");

        List<CustomerInteractionDTO> list = userService.getCustomerInteractions(50L);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getStaffName()).isEqualTo("CS Staff");
    }
}
