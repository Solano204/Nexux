package com.nexus.saga.infrastructure.jpa;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface SagaStepHistoryRepository
        extends JpaRepository<SagaStepHistory, UUID> {

    List<SagaStepHistory> findBySagaIdOrderByOccurredAtAsc(UUID sagaId);
}