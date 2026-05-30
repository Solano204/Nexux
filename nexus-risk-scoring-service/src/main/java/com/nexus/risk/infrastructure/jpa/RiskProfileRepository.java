package com.nexus.risk.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Risk Profile Repository — PostgreSQL queries for risk profiles.
 *
 * Key queries:
 * - findLatestByUserId: current valid profile for a user
 * - findLatestVersionByUserId: next version number for upsert
 * - findUsersNeedingRecomputation: batch job input
 * - findByUserIdOrderByComputedAtDesc: profile history for trends
 */
@Repository
public interface RiskProfileRepository
        extends JpaRepository<RiskProfileJpaEntity, UUID> {

    /**
     * Current valid profile for a user (most recent, not expired).
     */
    @Query("""
        SELECT r FROM RiskProfileJpaEntity r
        WHERE r.userId = :userId AND r.validUntil > CURRENT_TIMESTAMP
        ORDER BY r.computedAt DESC
        LIMIT 1
        """)
    Optional<RiskProfileJpaEntity> findLatestByUserId(
            @Param("userId") UUID userId);

    /**
     * Convenience: accepts String userId.
     */
    default Optional<RiskProfileJpaEntity> findLatestByUserId(String userId) {
        return findLatestByUserId(UUID.fromString(userId));
    }

    /**
     * Latest version number for a user (for version incrementing).
     */
    @Query("""
        SELECT MAX(r.version) FROM RiskProfileJpaEntity r
        WHERE r.userId = :userId
        """)
    Optional<Integer> findLatestVersionByUserId(
            @Param("userId") UUID userId);

    /**
     * Convenience: accepts String userId.
     */
    default Optional<Integer> findLatestVersionByUserId(String userId) {
        return findLatestVersionByUserId(UUID.fromString(userId));
    }

    /**
     * Profile history for a user (trend analysis).
     */
    List<RiskProfileJpaEntity> findByUserIdOrderByComputedAtDesc(
            UUID userId);

    /**
     * Users needing recomputation:
     * - No profile at all, OR
     * - Latest profile computed before the threshold
     *
     * Returns user IDs as strings for the batch job.
     */
    @Query(value = """
        SELECT DISTINCT u.user_id::text
        FROM (
            SELECT DISTINCT user_id FROM risk_profiles
            UNION
            SELECT user_id FROM risk_profiles
            WHERE computed_at < :threshold
        ) u
        LEFT JOIN LATERAL (
            SELECT computed_at FROM risk_profiles rp
            WHERE rp.user_id = u.user_id
            ORDER BY rp.computed_at DESC LIMIT 1
        ) latest ON true
        WHERE latest.computed_at IS NULL
           OR latest.computed_at < :threshold
        """, nativeQuery = true)
    List<String> findUsersNeedingRecomputation(
            @Param("threshold") Instant threshold);

    /**
     * Count profiles by risk tier (monitoring dashboard).
     */
    long countByRiskTier(String riskTier);
}