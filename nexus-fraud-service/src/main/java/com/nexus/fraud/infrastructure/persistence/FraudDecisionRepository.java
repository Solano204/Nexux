package com.nexus.fraud.infrastructure.persistence;

import com.nexus.fraud.domain.model.FraudDecisionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fraud Decision Repository — Immutable audit record access.
 *
 * Primary queries:
 * - Idempotency check: findByTransactionId (unique)
 * - User history: findByUserIdOrderByCreatedAtDesc
 * - Pending reviews: findPendingReviews
 * - SAR reporting: findSarFiled
 * - Risk analytics: findByDecisionOutcome, findByRiskScoreRange
 */
@Repository
public interface FraudDecisionRepository
        extends JpaRepository<FraudDecisionEntity, UUID> {

    Optional<FraudDecisionEntity> findByTransactionId(UUID transactionId);

    Page<FraudDecisionEntity> findByUserIdOrderByCreatedAtDesc(
            UUID userId, Pageable pageable);

    Page<FraudDecisionEntity> findByDecisionOutcomeOrderByCreatedAtDesc(
            String decisionOutcome, Pageable pageable);

    @Query("""
        SELECT d FROM FraudDecisionEntity d
        WHERE d.decisionOutcome = 'REVIEW'
        AND d.reviewedAt IS NULL
        ORDER BY d.reviewPriority ASC, d.createdAt ASC
        """)
    List<FraudDecisionEntity> findPendingReviews();

    @Query("""
        SELECT d FROM FraudDecisionEntity d
        WHERE d.riskScore BETWEEN :minScore AND :maxScore
        AND d.createdAt BETWEEN :from AND :to
        ORDER BY d.riskScore DESC
        """)
    List<FraudDecisionEntity> findByRiskScoreRange(
            @Param("minScore") java.math.BigDecimal minScore,
            @Param("maxScore") java.math.BigDecimal maxScore,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
        SELECT d FROM FraudDecisionEntity d
        WHERE d.sarFiled = true
        ORDER BY d.sarFiledAt DESC
        """)
    List<FraudDecisionEntity> findSarFiled();

    @Modifying
    @Query("""
        UPDATE FraudDecisionEntity d
        SET d.reviewedBy = :reviewerId,
            d.reviewOutcome = :outcome,
            d.reviewNotes = :notes,
            d.reviewedAt = :reviewedAt
        WHERE d.decisionId = :decisionId
        AND d.reviewedAt IS NULL
        """)
    int recordReviewOutcome(
            @Param("decisionId") UUID decisionId,
            @Param("reviewerId") UUID reviewerId,
            @Param("outcome") String outcome,
            @Param("notes") String notes,
            @Param("reviewedAt") Instant reviewedAt);

    @Modifying
    @Query("""
        UPDATE FraudDecisionEntity d
        SET d.sarFiled = true,
            d.sarFiledAt = :filedAt,
            d.sarReference = :reference
        WHERE d.decisionId = :decisionId
        """)
    int recordSarFiling(
            @Param("decisionId") UUID decisionId,
            @Param("filedAt") Instant filedAt,
            @Param("reference") String reference);

    long countByDecisionOutcomeAndCreatedAtAfter(
            String outcome, Instant after);
}