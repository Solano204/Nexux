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
 * and publishes to Kafka.
 *
 * The cleanup job uses findUnprocessedBefore() to delete
 * old entries after Debezium has confirmed delivery.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    /**
     * Find entries not yet cleaned up.
     * Used by the scheduled cleanup job — Debezium delivers independently.
     */
    @Query("""
        SELECT o FROM OutboxEntry o
        WHERE o.processedAt IS NULL
        AND o.createdAt < :before
        ORDER BY o.createdAt ASC
        """)
    List<OutboxEntry> findUnprocessedBefore(@Param("before") Instant before);

    /**
     * Mark entries as processed (cleanup job only).
     */
    @Modifying
    @Query("""
        UPDATE OutboxEntry o
        SET o.processedAt = :now
        WHERE o.outboxId IN :ids
        """)
    int markAsProcessed(@Param("ids") List<UUID> ids,
                        @Param("now") Instant now);

    /**
     * Find by aggregate for idempotency checks (rare).
     */
    List<OutboxEntry> findByAggregateIdAndEventType(
            UUID aggregateId, String eventType);
}