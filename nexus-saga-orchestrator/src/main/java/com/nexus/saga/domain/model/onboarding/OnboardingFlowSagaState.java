package com.nexus.saga.domain.model.onboarding;

import com.nexus.saga.domain.model.SagaFailureExplanation;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "onboarding_sagas")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OnboardingFlowSagaState {

    @Id
    @Column(name = "saga_id")
    private UUID sagaId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false)
    private OnboardingStep currentStep;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "failure_type")
    private String failureType;

    @Column(length = 5)
    private String language;

    @Column(name = "checking_account_id")
    private UUID checkingAccountId;

    @Column(name = "savings_account_id")
    private UUID savingsAccountId;

    @Type(JsonType.class)
    @Column(name = "failure_explanation", columnDefinition = "jsonb")
    private SagaFailureExplanation failureExplanation;

    @Column(name = "attempt_count")
    private int attemptCount;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @Version
    private int version;

    public boolean isTerminal() {
        return currentStep.isTerminal();
    }

    @PrePersist
    void prePersist() {
        if (sagaId == null) sagaId = UUID.randomUUID();
        if (startedAt == null) startedAt = Instant.now();
        if (lastUpdatedAt == null) lastUpdatedAt = Instant.now();
        if (language == null) language = "es";
        if (expiresAt == null) expiresAt = Instant.now()
                .plus(java.time.Duration.ofHours(48));
    }

    @PreUpdate
    void preUpdate() { this.lastUpdatedAt = Instant.now(); }
}