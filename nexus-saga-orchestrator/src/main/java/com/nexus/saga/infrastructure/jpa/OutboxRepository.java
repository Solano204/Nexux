package com.nexus.saga.infrastructure.jpa;

import com.nexus.saga.domain.model.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository
        extends JpaRepository<OutboxEntry, UUID> {

    /**
     * Kept for TransferSagaIntegrationTest, which uses it to assert an
     * entry was written. OutboxEntry.markProcessed() has no caller
     * anywhere in production code (Debezium reads the WAL directly, it
     * never sets processedAt), so in practice this always returns "all
     * entries" - functionally correct for the test's purpose, just a
     * stale name. Not renamed here to avoid touching passing test code
     * outside this prompt's scope. See
     * CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3.
     */
    List<OutboxEntry> findByProcessedAtIsNullOrderByCreatedAtAsc();

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.createdAt < :before")
    int deleteEntriesOlderThan(@Param("before") Instant before);
}