package com.nexus.saga.infrastructure.jpa;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SagaTimeoutRepository
        extends JpaRepository<SagaTimeout, UUID> {

    List<SagaTimeout> findByFiresAtBeforeAndIsCancelledFalseAndFiredAtIsNull(Instant now);

    List<SagaTimeout> findBySagaIdAndTimeoutTypeAndIsCancelledFalse(
            UUID sagaId, String timeoutType);

    List<SagaTimeout> findBySagaId(UUID sagaId);
}