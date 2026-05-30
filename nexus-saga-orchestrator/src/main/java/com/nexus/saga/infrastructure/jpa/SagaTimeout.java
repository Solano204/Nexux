package com.nexus.saga.infrastructure.jpa;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "saga_timeouts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SagaTimeout {
    @Id @Column(name = "timeout_id") private UUID timeoutId;
    @Column(name = "saga_id", nullable = false) private UUID sagaId;
    @Column(name = "saga_type", nullable = false) private String sagaType;
    @Column(name = "timeout_type", nullable = false) private String timeoutType;
    @Column(name = "fires_at", nullable = false) private Instant firesAt;
    @Column(name = "is_cancelled") private boolean isCancelled;
    @Column(name = "fired_at") private Instant firedAt;
    @Column(name = "created_at") private Instant createdAt;

    @PrePersist void prePersist() {
        if (timeoutId == null) timeoutId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}