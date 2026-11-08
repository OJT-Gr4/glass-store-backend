package com.glassstore.identity.repository;

import com.glassstore.identity.entity.OtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, Long> {
    Optional<OtpChallenge> findByChallengeCode(String challengeCode);

    List<OtpChallenge> findByUserIdAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
            Long userId, String purpose);

    long countByUserIdAndPurposeAndCreatedAtAfter(Long userId, String purpose, LocalDateTime since);
}

