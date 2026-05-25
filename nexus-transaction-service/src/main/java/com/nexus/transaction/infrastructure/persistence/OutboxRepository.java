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

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {

    @Query("SELECT o FROM OutboxEntry o WHERE o.processedAt IS NULL ORDER BY o.createdAt ASC")
    List<OutboxEntry> findUnprocessedEntries();

    @Query("SELECT COUNT(o) FROM OutboxEntry o WHERE o.processedAt IS NULL")
    long countUnprocessed();

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.processedAt IS NOT NULL AND o.processedAt < :before")
    int deleteProcessedEntriesBefore(@Param("before") Instant before);

    List<OutboxEntry> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
            String aggregateType, UUID aggregateId);
}