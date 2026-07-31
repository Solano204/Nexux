package com.nexus.ledger.infrastructure.persistence;

import com.nexus.ledger.domain.model.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * OutboxRepository — Debezium CDC event staging.
 *
 * Writes happen in the SAME transaction as domain changes (see
 * LedgerCommandService.postDoubleEntry). Debezium reads the PostgreSQL
 * WAL directly (not this table via a query) and publishes to Kafka - it
 * never updates any column on this table to mark a row as delivered.
 *
 * Had no cleanup query at all until this - see OutboxCleanupJob and
 * CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3: this table
 * had zero deletion mechanism, growing unbounded since the day this
 * service started writing to it.
 *
 * deleteEntriesOlderThan() deliberately does NOT filter on `processedAt`
 * (unlike account-service/transaction-service/identity-service's outbox
 * repositories, which all have a processedAt-based query that's equally
 * dead - confirmed by grepping the whole codebase for any place that
 * actually sets processedAt to non-null: there isn't one, on any of the
 * 6 outbox tables. That column exists for a poll-and-mark-as-processed
 * relay design that predates the move to Debezium CDC - Debezium doesn't
 * write back to this table at all, so `processedAt IS NOT NULL` matches
 * zero rows forever and any cleanup query built on it is a silent no-op).
 * The safe deletion criterion here is age alone: Debezium's WAL-based
 * capture happens within seconds of commit under normal operation, so a
 * multi-day buffer is a generous safety margin, not a race condition.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.createdAt < :before")
    int deleteEntriesOlderThan(@Param("before") Instant before);

    long countByCreatedAtBefore(Instant before);
}
