package com.nexus.kyc.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * KYC Audit Repository — PostgreSQL immutable audit log.
 *
 * Read + insert only. UPDATE/DELETE blocked by database trigger.
 * All queries are read-only for compliance reporting.
 */
@Repository
public interface KycAuditRepository
        extends JpaRepository<KycAuditEntryJPA, UUID> {

    List<KycAuditEntryJPA> findByVerificationIdOrderBySubmittedAtAsc(
            UUID verificationId);

    List<KycAuditEntryJPA> findByUserIdOrderBySubmittedAtDesc(
            UUID userId);

    List<KycAuditEntryJPA> findByDecisionAndSubmittedAtBetween(
            String decision, Instant start, Instant end);

    long countByUserIdAndDecision(UUID userId, String decision);
}