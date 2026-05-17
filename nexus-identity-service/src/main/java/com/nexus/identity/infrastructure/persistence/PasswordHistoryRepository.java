package com.nexus.identity.infrastructure.persistence;

import com.nexus.identity.domain.model.PasswordHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * PasswordHistoryRepository — last 5 password hashes per user.
 *
 * UserCommandService calls findTop5ByUserIdOrderByCreatedAtDesc()
 * then BCrypt.matches() against each hash to prevent password reuse.
 */
@Repository
public interface PasswordHistoryRepository
        extends JpaRepository<PasswordHistory, UUID> {

    /**
     * Returns the 5 most recent password hashes for the user.
     * Ordered newest-first for early exit in reuse check loop.
     */
    List<PasswordHistory> findTop5ByUserIdOrderByCreatedAtDesc(
            UUID userId);

    /**
     * Prune old history entries beyond the 5-entry window.
     * Called by a scheduled cleanup job (not hot path).
     */
    @Modifying
    @Query(value = """
        DELETE FROM password_history
        WHERE user_id = :userId
        AND history_id NOT IN (
            SELECT history_id FROM password_history
            WHERE user_id = :userId
            ORDER BY created_at DESC
            LIMIT 5
        )
        """, nativeQuery = true)
    int pruneOldHistoryForUser(@Param("userId") UUID userId);
}