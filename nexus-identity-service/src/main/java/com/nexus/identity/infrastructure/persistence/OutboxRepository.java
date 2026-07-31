package com.nexus.identity.infrastructure.persistence;

import com.nexus.identity.domain.model.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OutboxRepository — Transactional event staging.
 *
 * Writes happen in the SAME transaction as domain changes.
 * Debezium reads the PostgreSQL WAL (not this table directly)
 * and publishes to Kafka - it never updates any column here to mark a
 * row delivered.
 *
 * findUnprocessedBefore()/markAsProcessed() used to back this file's
 * "cleanup job uses findUnprocessedBefore()" doc comment above, but
 * grepping the whole codebase found no caller of markAsProcessed()
 * anywhere - dead code from a poll-and-mark-as-processed relay design
 * that predates the move to Debezium CDC. Same finding as the other 5
 * outbox tables on the platform. See
 * CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.createdAt < :before")
    int deleteEntriesOlderThan(@Param("before") Instant before);

    /**
     * Find by aggregate for idempotency checks (rare).
     */
    List<OutboxEntry> findByAggregateIdAndEventType(
            UUID aggregateId, String eventType);
}