package com.nexus.identity.infrastructure.persistence;

import com.nexus.identity.domain.model.KycVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KycVerificationRepository
        extends JpaRepository<KycVerification, UUID> {

    List<KycVerification> findByUserIdOrderByInitiatedAtDesc(UUID userId);

    Optional<KycVerification> findTopByUserIdOrderByAttemptNumberDesc(
            UUID userId);

    @Query("""
        SELECT COUNT(kv) FROM KycVerification kv
        WHERE kv.userId = :userId
        AND kv.finalDecision != 'TIMEOUT'
        """)
    int countAttemptsByUserId(@Param("userId") UUID userId);

    Optional<KycVerification> findByVerificationId(UUID verificationId);
}