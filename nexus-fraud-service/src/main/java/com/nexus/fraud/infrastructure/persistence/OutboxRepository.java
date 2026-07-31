package com.nexus.fraud.infrastructure.persistence;

import com.nexus.fraud.domain.model.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox Repository — Debezium CDC event staging.
 *
 * Outbox entries written in the same transaction as fraud decisions.
 * Debezium reads the PostgreSQL WAL directly and publishes to Kafka
 * (fraud.result, fraud.flagged topics) - it never writes back to this
 * table, so cleanup keys off createdAt age alone, not a processedAt
 * column. See CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3.
 */
@Repository
public interface OutboxRepository
        extends JpaRepository<OutboxEntry, UUID> {

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.createdAt < :before")
    int deleteEntriesOlderThan(@Param("before") Instant before);
}