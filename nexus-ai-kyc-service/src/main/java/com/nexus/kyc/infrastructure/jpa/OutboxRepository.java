package com.nexus.kyc.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Debezium reads kyc_outbox via the PostgreSQL WAL directly and never
 * writes back to this table, so cleanup keys off createdAt age alone, not
 * processed_at - same finding as the other 6 outbox tables on the platform.
 * See CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md Section 3.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.createdAt < :before")
    int deleteEntriesOlderThan(@Param("before") Instant before);
}
