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
 * Debezium CDC reads PostgreSQL WAL and publishes to Kafka.
 *
 * Cleanup operations:
 * - Processed entries older than 7 days are purged by scheduled job
 * - Unprocessed entries older than 24h trigger alerts (Debezium lag)
 *
 * This repository is primarily used for:
 * 1. Save (by AccountCommandService, in same TX as domain writes)
 * 2. Cleanup (by scheduled maintenance job)
 * 3. Monitoring (checking for stuck entries)
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    /**
     * Find unprocessed entries — indicates Debezium lag or failure.
     * Used by health monitoring and alerting.
     */
    @Query("""
        SELECT o FROM OutboxEntry o
        WHERE o.processedAt IS NULL
        ORDER BY o.createdAt ASC
        """)
    List<OutboxEntry> findUnprocessedEntries();

    /**
     * Find unprocessed entries older than threshold.
     * If any exist, Debezium may be down or lagging.
     */
    @Query("""
        SELECT o FROM OutboxEntry o
        WHERE o.processedAt IS NULL
        AND o.createdAt < :threshold
        ORDER BY o.createdAt ASC
        """)
    List<OutboxEntry> findStaleUnprocessedEntries(
            @Param("threshold") Instant threshold);

    /**
     * Count unprocessed entries — metric for monitoring dashboard.
     */
    @Query("SELECT COUNT(o) FROM OutboxEntry o WHERE o.processedAt IS NULL")
    long countUnprocessed();

    /**
     * Purge old processed entries (cleanup job).
     * Only deletes entries that have been successfully processed
     * by Debezium and are older than the retention period.
     */
    @Modifying
    @Query("""
        DELETE FROM OutboxEntry o
        WHERE o.processedAt IS NOT NULL
        AND o.processedAt < :before
        """)
    int deleteProcessedEntriesBefore(@Param("before") Instant before);

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