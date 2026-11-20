package com.glassstore.identity;
import com.glassstore.identity.dto.*;
import com.glassstore.identity.entity.*;
import com.glassstore.identity.repository.*;
import com.glassstore.identity.security.*;
import com.glassstore.identity.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StaffWorkBranchTest {
    final UserRepository users=mock(UserRepository.class);
    final PasswordEncoder encoder=mock(PasswordEncoder.class);
    final JwtTokenProvider jwt=new JwtTokenProvider("branch-test-0123456789012345678901234567890123456789",86400000);
    final UserServiceImpl service=new UserServiceImpl(users,mock(RoleRepository.class),mock(CustomerInteractionRepository.class),encoder,jwt,mock(BruteForceService.class));
    User setup(String role) {
        var user=User.builder().id(12L).username("qa").email("qa@example.com").passwordHash("hash").fullName("QA").status("ACTIVE").branchName("Chi nhánh 01").roles(Set.of(Role.builder().name(role).build())).build();
        when(users.findByEmail("qa")).thenReturn(Optional.of(user));when(users.findById(12L)).thenReturn(Optional.of(user));when(encoder.matches("password","hash")).thenReturn(true);return user;
    }
    @Test void selectedBranchIsSignedAndDoesNotChangeStaffHomeBranch() {
        var user=setup("ROLE_SALES_STAFF");var login=service.login(LoginRequest.builder().username("qa").password("password").branchCode("CN-02").build());
        assertEquals("BRANCH_02",login.getBranchCode());assertEquals("BRANCH_02",jwt.getClaimsFromToken(login.getToken()).get("branchCode"));
        assertEquals("BRANCH_02",jwt.getClaimsFromToken(login.getRefreshToken()).get("branchCode"));assertEquals("Chi nhánh 01",user.getBranchName());
        var refreshed=service.refreshToken(login.getRefreshToken());assertEquals("BRANCH_02",refreshed.getBranchCode());assertEquals("BRANCH_02",jwt.getClaimsFromToken(refreshed.getToken()).get("branchCode"));
    }
    @Test void customerLoginCannotGainAStaffWorkBranch() {
        setup("ROLE_CUSTOMER");var login=service.login(LoginRequest.builder().username("qa").password("password").branchCode("BRANCH_02").build());
        assertNull(login.getBranchCode());assertNull(jwt.getClaimsFromToken(login.getToken()).get("branchCode"));
    }
    @Test void invalidBranchIsRejectedAndNewLoginCanSelectAnotherBranch() {
        setup("ROLE_ADMIN");assertThrows(IllegalArgumentException.class,()->service.login(LoginRequest.builder().username("qa").password("password").branchCode("FAKE").build()));
        for(String code:List.of("BRANCH_01","BRANCH_02","LAB","HQ"))assertEquals(code,service.login(LoginRequest.builder().username("qa").password("password").branchCode(code).build()).getBranchCode());
    }
}
