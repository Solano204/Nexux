package com.nexus.identity.infrastructure.persistence;

import com.nexus.identity.domain.model.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByJtiAndIsActiveTrue(UUID jti);

    List<Session> findByUserIdAndIsActiveTrue(UUID userId);

    @Modifying
    @Query("""
        UPDATE Session s SET s.isActive = false
        WHERE s.userId = :userId AND s.isActive = true
        AND s.sessionId != :excludeSessionId
        """)
    int deactivateAllSessionsExcept(@Param("userId") UUID userId,
                                    @Param("excludeSessionId") UUID excludeSessionId);

    @Modifying
    @Query("""
        UPDATE Session s SET s.isActive = false
        WHERE s.userId = :userId AND s.isActive = true
        """)
    int deactivateAllSessions(@Param("userId") UUID userId);

    @Query("""
        SELECT s FROM Session s
        WHERE s.userId = :userId AND s.isActive = true
        ORDER BY s.issuedAt DESC
        """)
    List<Session> findActiveSessionsForUser(@Param("userId") UUID userId);

    @Modifying
    @Query("""
        UPDATE Session s SET s.lastActivityAt = :now
        WHERE s.sessionId = :sessionId
        """)
    int updateLastActivity(@Param("sessionId") UUID sessionId,
                           @Param("now") Instant now);
}