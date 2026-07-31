package com.nexus.saga.domain.model.transfer;

import com.nexus.saga.domain.model.SagaFailureExplanation;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TransferSagaState — JPA entity.
 * Optimistic locking via @Version prevents concurrent state corruption.
 * All state transitions validated by TransferStep.canTransitionTo().
 */
@Entity
@Table(name = "transfer_sagas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "sagaId")
public class TransferSagaState {

    @Id
    @Column(name = "saga_id", updatable = false)
    private UUID sagaId;

    @Column(name = "transaction_id", nullable = false,
            updatable = false, unique = true)
    private UUID transactionId;

    @Column(name = "source_account_id", nullable = false,
            updatable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false,
            updatable = false)
    private UUID targetAccountId;

    @Column(name = "source_user_id", nullable = false)
    private UUID sourceUserId;

    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "transaction_type", nullable = false)
    private String transactionType;

    private String description;
    private String merchantName;
    private String targetName;
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false)
    private TransferStep currentStep;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "new_available_balance",
            precision = 20, scale = 4)
    private BigDecimal newAvailableBalance;

    @Column(name = "funds_reserved")
    private boolean fundsReserved;

    @Column(name = "fraud_score", precision = 5, scale = 2)
    private BigDecimal fraudScore;

    @Column(name = "fraud_decision")
    private String fraudDecision;

    @Type(JsonType.class)
    @Column(name = "fraud_reject_reasons",
            columnDefinition = "jsonb")
    private List<String> fraudRejectReasons;

    @Column(name = "review_id")
    private UUID reviewId;

    @Column(name = "review_priority")
    private String reviewPriority;

    @Column(name = "ledger_posting_id")
    private UUID ledgerPostingId;

    @Column(name = "debit_entry_id")
    private UUID debitEntryId;

    @Column(name = "credit_entry_id")
    private UUID creditEntryId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "failure_type")
    private String failureType;

    @Type(JsonType.class)
    @Column(name = "failure_explanation", columnDefinition = "jsonb")
    private SagaFailureExplanation failureExplanation;

    @Column(name = "funds_released")
    private boolean fundsReleased;

    @Column(name = "compensation_attempts")
    private int compensationAttempts;

    /**
     * Retry counter for BALANCE_FINALIZE only (post-pivot, after LEDGER_POSTED).
     * Separate from compensationAttempts because it drives a retry-the-idempotent-
     * command loop, not a compensation loop — see TransferSagaProcessor.retryFinalizeOrEscalate.
     */
    @Column(name = "finalize_retry_count")
    private int finalizeRetryCount;

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
        if (sagaId == null) sagaId = UUID.randomUUID();
        if (startedAt == null) startedAt = Instant.now();
        if (lastUpdatedAt == null) lastUpdatedAt = Instant.now();
        if (language == null) language = "es";
        if (expiresAt == null)
            expiresAt = Instant.now()
                    .plus(java.time.Duration.ofHours(24));
    }

    @PreUpdate
    void preUpdate() {
        this.lastUpdatedAt = Instant.now();
    }

    public boolean isTerminal() {
        return currentStep.isTerminal();
    }
}