package com.nexus.transaction.domain.model;

import com.nexus.transaction.domain.exception.*;
import com.nexus.transaction.domain.model.enums.*;
import io.hypersistence.utils.hibernate.type.array.ListArrayType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transaction — Core domain entity with 16-state lifecycle.
 *
 * State machine is enforced at TWO layers:
 * 1. Application layer: transition() validates before each update
 * 2. Database layer: PostgreSQL trigger rejects invalid transitions
 *
 * This entity represents the authoritative record of a financial
 * transaction from initiation through completion or failure.
 *
 * Pattern: State Machine Pattern — explicit lifecycle with transitions
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "transactionId")
public class Transaction {

    @Id
    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id")
    private UUID targetAccountId;

    @Column(name = "target_account_number")
    private String targetAccountNumber;

    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "exchange_rate", precision = 10, scale = 6)
    private BigDecimal exchangeRate;

    @Column(name = "fee_amount", precision = 20, scale = 4)
    private BigDecimal feeAmount;

    // net_amount is GENERATED ALWAYS in DB — not written by app
    @Column(name = "net_amount",
            insertable = false, updatable = false,
            precision = 20, scale = 4)
    private BigDecimal netAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionChannel channel;

    @Column(length = 500)
    private String description;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "merchant_category_code", length = 4)
    private String merchantCategoryCode;

    @Column(name = "reference_number")
    private String referenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    // Fraud intelligence
    @Column(name = "fraud_score", precision = 5, scale = 4)
    private BigDecimal fraudScore;

    @Column(name = "fraud_decision", length = 20)
    private String fraudDecision;

    @Type(ListArrayType.class)
    @Column(name = "fraud_reasons", columnDefinition = "text[]")
    private List<String> fraudReasons;

    @Column(name = "fraud_checked_at")
    private Instant fraudCheckedAt;

    // Ledger reference
    @Column(name = "ledger_entry_id")
    private UUID ledgerEntryId;

    // SAGA tracking
    @Column(name = "saga_id")
    private UUID sagaId;

    @Column(name = "saga_step", length = 50)
    private String sagaStep;

    // Request context
    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "device_fingerprint")
    private String deviceFingerprint;

    // Timing
    @Column(name = "initiated_at", updatable = false)
    private Instant initiatedAt;

    @Column(name = "fraud_check_started_at")
    private Instant fraudCheckStartedAt;

    @Column(name = "balance_reserved_at")
    private Instant balanceReservedAt;

    @Column(name = "ledger_posted_at_ts")
    private Instant ledgerPostedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    private int version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (transactionId == null) transactionId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (initiatedAt == null) initiatedAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = TransactionStatus.INITIATED;
        if (feeAmount == null) feeAmount = BigDecimal.ZERO;
        if (exchangeRate == null)
            exchangeRate = BigDecimal.ONE;
        if (channel == null) channel = TransactionChannel.API;
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    // ════════════════════════════════════════════════════════
    // STATE MACHINE — application-layer enforcement
    // ════════════════════════════════════════════════════════

    /**
     * Transitions the transaction to a new state.
     * Validates the transition is legal before applying.
     * Database trigger provides the second enforcement layer.
     */
    public void transition(TransactionStatus newStatus) {
        if (!isValidTransition(this.status, newStatus)) {
            throw new InvalidTransactionStateException(
                    String.format(
                            "Invalid transition: %s → %s for txn %s",
                            this.status, newStatus, this.transactionId));
        }
        this.status = newStatus;
    }

    public void markFraudChecking() {
        transition(TransactionStatus.FRAUD_CHECKING);
        this.fraudCheckStartedAt = Instant.now();
        this.sagaStep = "FRAUD_CHECKING";
    }

    public void markFraudCleared(BigDecimal score,
                                 List<String> reasons) {
        transition(TransactionStatus.FRAUD_CLEARED);
        this.fraudScore = score;
        this.fraudDecision = "CLEARED";
        this.fraudReasons = reasons;
        this.fraudCheckedAt = Instant.now();
        this.sagaStep = "FRAUD_CLEARED";
    }

    public void markFraudRejected(BigDecimal score,
                                  List<String> reasons) {
        transition(TransactionStatus.FRAUD_REJECTED);
        this.fraudScore = score;
        this.fraudDecision = "REJECTED";
        this.fraudReasons = reasons;
        this.fraudCheckedAt = Instant.now();
        this.failedAt = Instant.now();
        this.failureReason = "FRAUD_REJECTED: " + reasons;
        this.sagaStep = "FRAUD_REJECTED";
    }

    public void markBalanceReserving() {
        transition(TransactionStatus.BALANCE_RESERVING);
        this.sagaStep = "BALANCE_RESERVING";
    }

    public void markBalanceReserved() {
        transition(TransactionStatus.BALANCE_RESERVED);
        this.balanceReservedAt = Instant.now();
        this.sagaStep = "BALANCE_RESERVED";
    }

    public void markReserveFailed(String reason) {
        transition(TransactionStatus.RESERVE_FAILED);
        this.failureReason = reason;
        this.sagaStep = "RESERVE_FAILED";
    }

    public void markLedgerPosting() {
        transition(TransactionStatus.LEDGER_POSTING);
        this.sagaStep = "LEDGER_POSTING";
    }

    public void markLedgerPosted(UUID ledgerEntryId) {
        transition(TransactionStatus.LEDGER_POSTED);
        this.ledgerEntryId = ledgerEntryId;
        this.ledgerPostedAt = Instant.now();
        this.sagaStep = "LEDGER_POSTED";
    }

    public void markLedgerFailed(String reason) {
        transition(TransactionStatus.LEDGER_FAILED);
        this.failureReason = reason;
        this.sagaStep = "LEDGER_FAILED";
    }

    public void markCompleted() {
        transition(TransactionStatus.COMPLETING);
        transition(TransactionStatus.COMPLETED);
        this.completedAt = Instant.now();
        this.sagaStep = "COMPLETED";
    }

    public void markFailed(String reason) {
        if (!isTerminalStatus(this.status)) {
            this.status = TransactionStatus.FAILED;
            this.failedAt = Instant.now();
            this.failureReason = reason;
            this.sagaStep = "FAILED";
        }
    }

    public void markReversed() {
        transition(TransactionStatus.REVERSING);
        transition(TransactionStatus.REVERSED);
        this.sagaStep = "REVERSED";
    }

    // ─── State machine helpers ────────────────────────────

    private static boolean isValidTransition(
            TransactionStatus from, TransactionStatus to) {
        return switch (from) {
            case INITIATED -> to == TransactionStatus.FRAUD_CHECKING
                    || to == TransactionStatus.CANCELLED;
            case FRAUD_CHECKING -> to == TransactionStatus.FRAUD_CLEARED
                    || to == TransactionStatus.FRAUD_REJECTED;
            case FRAUD_CLEARED -> to == TransactionStatus.BALANCE_RESERVING;
            case BALANCE_RESERVING ->
                    to == TransactionStatus.BALANCE_RESERVED
                            || to == TransactionStatus.RESERVE_FAILED;
            case BALANCE_RESERVED -> to == TransactionStatus.LEDGER_POSTING;
            case LEDGER_POSTING -> to == TransactionStatus.LEDGER_POSTED
                    || to == TransactionStatus.LEDGER_FAILED;
            case LEDGER_POSTED -> to == TransactionStatus.COMPLETING;
            case COMPLETING -> to == TransactionStatus.COMPLETED;
            case RESERVE_FAILED -> to == TransactionStatus.FAILED;
            case LEDGER_FAILED -> to == TransactionStatus.REVERSING;
            case REVERSING -> to == TransactionStatus.REVERSED;
            case FRAUD_REJECTED -> to == TransactionStatus.FAILED;
            default -> false; // Terminal states have no transitions
        };
    }

    public static boolean isTerminalStatus(TransactionStatus status) {
        return switch (status) {
            case COMPLETED, FAILED, REVERSED,
                 CANCELLED, FRAUD_REJECTED -> true;
            default -> false;
        };
    }
}