package com.glassstore.identity.service;

import com.glassstore.identity.dto.*;

import java.util.List;

public interface UserService {
    List<UserDTO> getAllStaff();
    List<UserDTO> getAllCustomers();
    UserDTO getUserById(Long id);
    UserDTO createStaff(CreateStaffRequest request);
    UserDTO createCustomer(CreateCustomerRequest request);
    UserDTO toggleUserStatus(Long id);
    void deleteUser(Long id);
    LoginResponse login(LoginRequest request);

    // Customer CRUD, consent & interaction history (S3-04)
    UserDTO updateCustomer(Long id, UpdateCustomerRequest request);
    List<UserDTO> searchCustomers(String query);
    List<CustomerInteractionDTO> getCustomerInteractions(Long customerId);
    CustomerInteractionDTO addCustomerInteraction(Long customerId, Long staffId, String staffName, CreateInteractionRequest request);

    // Phương thức Authentication không OTP mới
    UserDTO registerCustomer(CustomerRegisterRequest request);
    LoginResponse refreshToken(String refreshToken);
    UserDTO getCurrentUser(Long userId);
}
