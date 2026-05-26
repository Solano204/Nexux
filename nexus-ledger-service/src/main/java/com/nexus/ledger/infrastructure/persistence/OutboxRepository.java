package com.nexus.ledger.infrastructure.persistence;

import com.nexus.ledger.domain.model.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEntry, UUID> {
}