package com.nexus.saga.infrastructure.jpa;

import com.nexus.saga.domain.model.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository
        extends JpaRepository<OutboxEntry, UUID> {

    List<OutboxEntry> findByProcessedAtIsNullOrderByCreatedAtAsc();
}