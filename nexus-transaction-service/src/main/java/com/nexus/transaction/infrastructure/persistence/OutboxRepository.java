package com.nexus.transaction.infrastructure.persistence;

import com.nexus.transaction.domain.model.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The processedAt-based methods this interface used to have
 * (findUnprocessedEntries/countUnprocessed/deleteProcessedEntriesBefore)
 * were dead code: nothing in this service ever sets processedAt to
 * non-null - Debezium reads the WAL directly and never writes back to
 * this table. Same finding as the other 5 outbox tables on the platform.
 * See CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.createdAt < :before")
    int deleteEntriesOlderThan(@Param("before") Instant before);

    List<OutboxEntry> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
            String aggregateType, UUID aggregateId);
}