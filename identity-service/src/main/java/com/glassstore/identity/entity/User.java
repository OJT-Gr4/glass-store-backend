package com.glassstore.identity.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "employee_code", length = 50)
    private String employeeCode;

    @Column(name = "branch_name", length = 100)
    private String branchName;

    @Column(length = 20)
    private String status = "ACTIVE";

    @Column(name = "failed_attempts", nullable = false, columnDefinition = "int default 0")
    private int failedAttempts = 0;

    @Column(name = "lockout_expiration")
    private LocalDateTime lockoutExpiration;

    @Column(name = "phone_verified", nullable = false, columnDefinition = "boolean default false")
    private boolean phoneVerified = false;

    @Column(name = "token_version", nullable = false, columnDefinition = "int default 0")
    private int tokenVersion = 0;

    @Column(name = "marketing_consent", nullable = false, columnDefinition = "boolean default false")
    private boolean marketingConsent = false;

    @Column(name = "marketing_consent_updated_at")
    private LocalDateTime marketingConsentUpdatedAt;

    @Column(name = "gender", length = 20)
    private String gender;

    @Column(name = "address", length = 500)
    private String address;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public User() {}

    public User(Long id, String username, String email, String passwordHash, String fullName, String phoneNumber, String employeeCode, String branchName, String status, int failedAttempts, LocalDateTime lockoutExpiration, Set<Role> roles) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.phoneNumber = phoneNumber;
        this.employeeCode = employeeCode;
        this.branchName = branchName;
        this.status = status != null ? status : "ACTIVE";
        this.failedAttempts = failedAttempts;
        this.lockoutExpiration = lockoutExpiration;
        this.roles = roles != null ? roles : new HashSet<>();
    }

    public static UserBuilder builder() {
        return new UserBuilder();
    }

    public static class UserBuilder {
        private Long id;
        private String username;
        private String email;
        private String passwordHash;
        private String fullName;
        private String phoneNumber;
        private String employeeCode;
        private String branchName;
        private String status = "ACTIVE";
        private int failedAttempts = 0;
        private LocalDateTime lockoutExpiration;
        private boolean phoneVerified = false;
        private int tokenVersion = 0;
        private boolean marketingConsent = false;
        private LocalDateTime marketingConsentUpdatedAt;
        private String gender;
        private String address;
        private Set<Role> roles = new HashSet<>();
        

        public UserBuilder id(Long id) { this.id = id; return this; }
        public UserBuilder username(String username) { this.username = username; return this; }
        public UserBuilder email(String email) { this.email = email; return this; }
        public UserBuilder passwordHash(String passwordHash) { this.passwordHash = passwordHash; return this; }
        public UserBuilder fullName(String fullName) { this.fullName = fullName; return this; }
        public UserBuilder phoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; return this; }
        public UserBuilder employeeCode(String employeeCode) { this.employeeCode = employeeCode; return this; }
        public UserBuilder branchName(String branchName) { this.branchName = branchName; return this; }
        public UserBuilder status(String status) { this.status = status; return this; }
        public UserBuilder failedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; return this; }
        public UserBuilder lockoutExpiration(LocalDateTime lockoutExpiration) { this.lockoutExpiration = lockoutExpiration; return this; }
        public UserBuilder phoneVerified(boolean phoneVerified) { this.phoneVerified = phoneVerified; return this; }
        public UserBuilder tokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; return this; }
        public UserBuilder marketingConsent(boolean marketingConsent) { this.marketingConsent = marketingConsent; return this; }
        public UserBuilder marketingConsentUpdatedAt(LocalDateTime marketingConsentUpdatedAt) { this.marketingConsentUpdatedAt = marketingConsentUpdatedAt; return this; }
        public UserBuilder gender(String gender) { this.gender = gender; return this; }
        public UserBuilder address(String address) { this.address = address; return this; }
        public UserBuilder roles(Set<Role> roles) { this.roles = roles; return this; }

        public User build() {
            User u = new User(id, username, email, passwordHash, fullName, phoneNumber, employeeCode, branchName, status, failedAttempts, lockoutExpiration, roles);
            u.setPhoneVerified(phoneVerified);
            u.setTokenVersion(tokenVersion);
            u.setMarketingConsent(marketingConsent);
            u.setMarketingConsentUpdatedAt(marketingConsentUpdatedAt);
            u.setGender(gender);
            u.setAddress(address);
            return u;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
    public LocalDateTime getLockoutExpiration() { return lockoutExpiration; }
    public void setLockoutExpiration(LocalDateTime lockoutExpiration) { this.lockoutExpiration = lockoutExpiration; }
    public boolean isPhoneVerified() { return phoneVerified; }
    public void setPhoneVerified(boolean phoneVerified) { this.phoneVerified = phoneVerified; }
    public int getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; }
    public boolean isMarketingConsent() { return marketingConsent; }
    public void setMarketingConsent(boolean marketingConsent) { this.marketingConsent = marketingConsent; }
    public LocalDateTime getMarketingConsentUpdatedAt() { return marketingConsentUpdatedAt; }
    public void setMarketingConsentUpdatedAt(LocalDateTime marketingConsentUpdatedAt) { this.marketingConsentUpdatedAt = marketingConsentUpdatedAt; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean isLocked() {
        if (lockoutExpiration == null) return false;
        return LocalDateTime.now().isBefore(lockoutExpiration);
    }
}
