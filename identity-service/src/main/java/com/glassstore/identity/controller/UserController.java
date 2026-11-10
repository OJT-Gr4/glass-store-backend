package com.glassstore.identity.controller;

import com.glassstore.common.dto.ApiResponse;
import com.glassstore.identity.dto.*;
import com.glassstore.identity.security.UserPrincipal;
import com.glassstore.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/api/v1/users", "/api/users"})
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class UserController {

    private final UserService userService;

    @GetMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<UserDTO>>> getAllStaff() {
        List<UserDTO> staff = userService.getAllStaff();
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách nhân viên thành công", staff));
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_STAFF', 'OPTOMETRIST')")
    public ResponseEntity<ApiResponse<List<UserDTO>>> getAllCustomers() {
        List<UserDTO> customers = userService.getAllCustomers();
        return ResponseEntity.ok(ApiResponse.success("Lấy danh sách khách hàng thành công", customers));
    }

    @GetMapping("/customers/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_STAFF', 'OPTOMETRIST')")
    public ResponseEntity<ApiResponse<List<UserDTO>>> searchCustomers(@RequestParam(name = "q", required = false) String query) {
        List<UserDTO> customers = userService.searchCustomers(query);
        return ResponseEntity.ok(ApiResponse.success("Tìm kiếm khách hàng thành công", customers));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_STAFF','OPTOMETRIST') or #id == authentication.principal.id")
    public ResponseEntity<ApiResponse<UserDTO>> getUserById(@PathVariable("id") Long id) {
        UserDTO user = userService.getUserById(id);
        return ResponseEntity.ok(ApiResponse.success("Lấy thông tin người dùng thành công", user));
    }

    @PostMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserDTO>> createStaff(@RequestBody CreateStaffRequest request) {
        UserDTO user = userService.createStaff(request);
        return ResponseEntity.ok(ApiResponse.success("Tạo nhân viên mới thành công", user));
    }

    @PostMapping("/customers")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_STAFF','OPTOMETRIST')")
    public ResponseEntity<ApiResponse<UserDTO>> createCustomer(@RequestBody CreateCustomerRequest request) {
        UserDTO user = userService.createCustomer(request);
        return ResponseEntity.ok(ApiResponse.success("Tạo hồ sơ khách hàng thành công", user));
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_STAFF','OPTOMETRIST') or #id == authentication.principal.id")
    public ResponseEntity<ApiResponse<UserDTO>> updateCustomer(
            @PathVariable("id") Long id,
            @RequestBody UpdateCustomerRequest request) {
        UserDTO user = userService.updateCustomer(id, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật hồ sơ khách hàng thành công", user));
    }

    @GetMapping("/customers/{id}/interactions")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_STAFF','OPTOMETRIST') or #id == authentication.principal.id")
    public ResponseEntity<ApiResponse<List<CustomerInteractionDTO>>> getCustomerInteractions(@PathVariable("id") Long id) {
        List<CustomerInteractionDTO> interactions = userService.getCustomerInteractions(id);
        return ResponseEntity.ok(ApiResponse.success("Lấy lịch sử chăm sóc khách hàng thành công", interactions));
    }

    @PostMapping("/customers/{id}/interactions")
    @PreAuthorize("hasAnyRole('ADMIN','SALES_STAFF','OPTOMETRIST')")
    public ResponseEntity<ApiResponse<CustomerInteractionDTO>> addCustomerInteraction(
            @PathVariable("id") Long id,
            @RequestBody CreateInteractionRequest request,
            Authentication authentication) {
        Long staffId = null;
        String staffName = "Nhân viên CRM";
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            staffId = principal.getId();
            staffName = principal.getUsername();
        }
        CustomerInteractionDTO result = userService.addCustomerInteraction(id, staffId, staffName, request);
        return ResponseEntity.ok(ApiResponse.success("Thêm ghi chú chăm sóc thành công", result));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserDTO>> toggleUserStatus(@PathVariable("id") Long id) {
        UserDTO user = userService.toggleUserStatus(id);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật trạng thái người dùng thành công", user));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable("id") Long id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success("Xóa người dùng thành công", null));
    }
}
