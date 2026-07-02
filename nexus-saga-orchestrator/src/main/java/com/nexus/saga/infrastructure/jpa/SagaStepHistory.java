package com.nexus.saga.infrastructure.jpa;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "saga_step_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SagaStepHistory {
    @Id @Column(name = "history_id")
    private UUID historyId;
    @Column(name = "saga_id", nullable = false) private UUID sagaId;
    @Column(name = "saga_type", nullable = false) private String sagaType;
    @Column(name = "from_step") private String fromStep;
    @Column(name = "to_step", nullable = false) private String toStep;
    private String reason;
    @Column(name = "occurred_at") private Instant occurredAt;
    @Column(name = "duration_ms") private Integer durationMs;
    @Column(name = "trace_id") private String traceId;

    @PrePersist void prePersist() {
        if (historyId == null) historyId = UUID.randomUUID();
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
