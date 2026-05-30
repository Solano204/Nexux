package com.nexus.kyc.infrastructure.mongodb;

import com.nexus.kyc.domain.model.enums.KycStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * KYC Document MongoDB Repository.
 *
 * All queries are against KycDocumentMongoDB (collection: kyc_documents).
 *
 * Index coverage:
 *   - userId            → findByUserId*, countByUserId*
 *   - submittedAt       → findBySubmittedAtBetween*
 *   - (userId, status)  → countByUserIdAndStatus, findByUserIdAndStatus
 */
@Repository
public interface KycDocumentRepository
        extends MongoRepository<KycDocumentMongoDB, String> {

    // ── User-scoped queries ───────────────────────────────────

    /** All verification attempts for a user, newest first. */
    List<KycDocumentMongoDB> findByUserIdOrderBySubmittedAtDesc(
            String userId);

    /** Lookup by verificationId + userId (ownership check). */
    Optional<KycDocumentMongoDB> findByVerificationIdAndUserId(
            String verificationId, String userId);

    /** Total attempt count for a user (retry limit enforcement). */
    long countByUserId(String userId);

    /** Attempt count for a user filtered by status. */
    long countByUserIdAndStatus(String userId, KycStatus status);

    /** Most recent attempt for a user regardless of status. */
    Optional<KycDocumentMongoDB> findFirstByUserIdOrderBySubmittedAtDesc(
            String userId);

    // ── Admin / compliance queries ────────────────────────────

    /** All documents with a given status (compliance dashboard). */
    List<KycDocumentMongoDB> findByStatus(KycStatus status);

    /**
     * Time-range query filtered by status.
     * Used by compliance reports and daily batch jobs.
     */
    List<KycDocumentMongoDB> findBySubmittedAtBetweenAndStatus(
            Instant start, Instant end, KycStatus status);
}