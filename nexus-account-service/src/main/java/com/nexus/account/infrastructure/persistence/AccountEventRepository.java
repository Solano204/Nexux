package com.nexus.account.infrastructure.persistence;

import com.nexus.account.domain.model.AccountEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AccountEventRepository — Read-only data access for account events.
 *
 * account_events table is immutable (PostgreSQL trigger prevents UPDATE/DELETE).
 * This repository only supports save() and find operations.
 * The immutability trigger in V3 migration is the database-level enforcement.
 *
 * Used by:
 * - AccountCommandService: writes events after every balance operation
 * - AccountQueryService: reads event history for UI display
 * - AccountAnalyticsDocument: periodic aggregation into MongoDB
 */
@Repository
public interface AccountEventRepository extends JpaRepository<AccountEvent, UUID> {

    /**
     * Paginated event history for an account, newest first.
     * Used by GET /api/v1/accounts/{id}/events endpoint.
     */
    Page<AccountEvent> findByAccountIdOrderByOccurredAtDesc(
            UUID accountId, Pageable pageable);

    /**
     * All events for a specific transaction.
     * Used for transaction audit trail reconstruction.
     */
    List<AccountEvent> findByTransactionIdOrderByOccurredAtAsc(UUID transactionId);

    /**
     * Events by type for an account within a date range.
     * Used by analytics aggregation jobs.
     */
    @Query("""
        SELECT e FROM AccountEvent e
        WHERE e.accountId = :accountId
        AND e.eventType = :eventType
        AND e.occurredAt BETWEEN :from AND :to
        ORDER BY e.occurredAt DESC
        """)
    List<AccountEvent> findByAccountIdAndEventTypeAndDateRange(
            @Param("accountId") UUID accountId,
            @Param("eventType") String eventType,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * Count events by type for an account.
     * Used for analytics dashboard metrics.
     */
    long countByAccountIdAndEventType(UUID accountId, String eventType);

    /**
     * Recent events across all accounts — admin/monitoring view.
     */
    @Query("""
        SELECT e FROM AccountEvent e
        WHERE e.occurredAt >= :since
        ORDER BY e.occurredAt DESC
        """)
    List<AccountEvent> findRecentEvents(@Param("since") Instant since);

    /**
     * Events for a specific account since a given timestamp.
     * Used by cache refresh and incremental analytics sync.
     */
    List<AccountEvent> findByAccountIdAndOccurredAtAfterOrderByOccurredAtAsc(
            UUID accountId, Instant since);
}