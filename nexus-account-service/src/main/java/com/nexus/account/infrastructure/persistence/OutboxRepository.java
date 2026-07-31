package com.nexus.account.infrastructure.persistence;

import com.nexus.account.domain.model.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OutboxRepository — Data access for Outbox Pattern entries.
 *
 * Outbox entries are written atomically with domain changes.
 * Debezium CDC reads the PostgreSQL WAL directly (not this table via a
 * query) and publishes to Kafka - it never updates any column here to
 * mark a row delivered.
 *
 * The processedAt-based methods this interface used to have
 * (findUnprocessedEntries/findStaleUnprocessedEntries/countUnprocessed/
 * deleteProcessedEntriesBefore) were dead code: confirmed by grepping the
 * whole codebase for anywhere that sets processedAt to non-null - there
 * isn't one, on any of the 6 services with an outbox table. That column
 * is a leftover from a poll-and-mark-as-processed relay design that
 * predates the move to Debezium CDC. A cleanup query built on
 * `processedAt IS NOT NULL` matches zero rows forever - not a bug that
 * throws, just a job that silently never deletes anything, which is how
 * this table grew unbounded with no cleanup ever actually running. See
 * OutboxCleanupJob and CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md
 * Section 3.
 *
 * deleteEntriesOlderThan() uses age alone as the safe deletion criterion:
 * Debezium's WAL-based capture happens within seconds of commit under
 * normal operation, so a multi-day retention buffer is a generous safety
 * margin, not a race condition.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.createdAt < :before")
    int deleteEntriesOlderThan(@Param("before") Instant before);

    /**
     * Find entries by aggregate for debugging/replay.
     */
    List<OutboxEntry> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
            String aggregateType, UUID aggregateId);

    /**
     * Find entries by event type — useful for selective replay.
     */
    List<OutboxEntry> findByEventTypeOrderByCreatedAtAsc(String eventType);
}