package com.glassstore.identity.repository;

import com.glassstore.identity.entity.OtpVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OtpVerificationRepository extends JpaRepository<OtpVerification, Long> {
    Optional<OtpVerification> findTopByEmailAndUsedFalseOrderByCreatedAtDesc(String email);

    Optional<OtpVerification> findByEmailAndOtpCodeAndUsedFalse(String email, String otpCode);
}
