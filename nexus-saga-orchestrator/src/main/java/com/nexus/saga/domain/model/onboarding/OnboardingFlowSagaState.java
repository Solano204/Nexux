package com.nexus.saga.domain.model.onboarding;

import com.nexus.saga.domain.model.SagaFailureExplanation;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * OnboardingFlowSagaState — JPA entity.
 *
 * Tracks the full lifecycle of a user's onboarding journey:
 * registration → KYC → account creation → welcome notification.
 *
 * Unique index on (user_id WHERE completed_at IS NULL) ensures only one
 * active onboarding saga per user at any time.
 *
 * Optimistic locking via @Version prevents concurrent state corruption.
 */
@Entity
@Table(name = "onboarding_sagas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "sagaId")
@ToString(exclude = "failureExplanation")
public class OnboardingFlowSagaState {

    @Id
    @Column(name = "saga_id", updatable = false)
    private UUID sagaId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "email")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false)
    private OnboardingStep currentStep;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "failure_type")
    private String failureType;

    @Column(name = "language", nullable = false)
    private String language;

    /** Set when ACCOUNTS_CREATING → ACCOUNTS_CREATED */
    @Column(name = "checking_account_id")
    private UUID checkingAccountId;

    @Column(name = "savings_account_id")
    private UUID savingsAccountId;

    /** AI-generated user-facing explanation, stored as JSONB */
    @Type(JsonType.class)
    @Column(name = "failure_explanation", columnDefinition = "jsonb")
    private SagaFailureExplanation failureExplanation;

    @Column(name = "attempt_count", nullable = false)
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

    @PrePersist
    void prePersist() {
        if (sagaId == null)       sagaId       = UUID.randomUUID();
        if (startedAt == null)    startedAt    = Instant.now();
        if (lastUpdatedAt == null) lastUpdatedAt = Instant.now();
        if (language == null)     language     = "es";
        if (expiresAt == null)    expiresAt    =
                Instant.now().plus(Duration.ofHours(48));
    }

    @PreUpdate
    void preUpdate() {
        this.lastUpdatedAt = Instant.now();
    }

    // ── Convenience methods ───────────────────────────────────

    public boolean isTerminal() {
        return currentStep.isTerminal();
    }

    public OnboardingFlowSagaState withAccountIds(
            UUID checkingAccountId, UUID savingsAccountId) {
        this.checkingAccountId = checkingAccountId;
        this.savingsAccountId  = savingsAccountId;
        return this;
    }

    public OnboardingFlowSagaState withCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
        return this;
    }

    public OnboardingFlowSagaState withFailureExplanation(
            SagaFailureExplanation explanation) {
        this.failureExplanation = explanation;
        return this;
    }

    public long durationMs() {
        Instant end = completedAt != null ? completedAt : Instant.now();
        return Duration.between(startedAt, end).toMillis();
    }
}