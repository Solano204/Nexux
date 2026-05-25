package com.nexus.fraud.infrastructure.persistence;

import com.nexus.fraud.domain.model.OutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Outbox Repository — Debezium CDC event staging.
 *
 * Outbox entries written in the same transaction as fraud decisions.
 * Debezium reads and publishes to Kafka (fraud.flagged topic).
 */
@Repository
public interface OutboxRepository
        extends JpaRepository<OutboxEntry, UUID> {
}