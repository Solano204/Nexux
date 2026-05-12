package com.nexus.account.domain.model;

import com.nexus.account.domain.exception.*;
import com.nexus.account.domain.model.enums.AccountStatus;
import com.nexus.account.domain.model.enums.AccountType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Account — DDD Aggregate Root.
 *
 * This is the most critical domain object in the platform.
 * ALL balance modifications MUST go through this aggregate's methods.
 * No external code modifies balance fields directly.
 *
 * Core invariants enforced at application layer:
 * - availableBalance >= 0 always
 * - reservedAmount >= 0 always
 * - totalBalance == availableBalance + reservedAmount always
 *
 * Backed by PostgreSQL CHECK constraints as final defense layer.
 *
 * Pattern: DDD Aggregate Root — single entry point for all state changes
 * Pattern: Domain Events — every state change records an event
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "accountId")
@ToString(exclude = {"domainEvents"})
public class Account {

    @Id
    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Column(name = "account_number", nullable = false, unique = true,
            updatable = false)
    private String accountNumber;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountType accountType;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    // ── Balance fields — ONLY modified through aggregate methods ──

    @Column(name = "available_balance", nullable = false,
            precision = 20, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "reserved_amount", nullable = false,
            precision = 20, scale = 4)
    private BigDecimal reservedAmount;

    // total_balance is a PostgreSQL GENERATED column — not mapped for writes
    @Column(name = "total_balance", insertable = false, updatable = false,
            precision = 20, scale = 4)
    private BigDecimal totalBalance;

    @Column(name = "pending_credit", precision = 20, scale = 4)
    private BigDecimal pendingCredit;

    // ── Limits ──────────────────────────────────────────────────

    @Column(name = "daily_transaction_limit", precision = 20, scale = 4)
    private BigDecimal dailyTransactionLimit;

    @Column(name = "daily_transaction_used", precision = 20, scale = 4)
    private BigDecimal dailyTransactionUsed;

    @Column(name = "daily_reset_at")
    private Instant dailyResetAt;

    @Column(name = "monthly_transaction_limit", precision = 20, scale = 4)
    private BigDecimal monthlyTransactionLimit;

    @Column(name = "monthly_transaction_used", precision = 20, scale = 4)
    private BigDecimal monthlyTransactionUsed;

    @Column(name = "minimum_balance", precision = 20, scale = 4)
    private BigDecimal minimumBalance;

    @Column(name = "interest_rate", precision = 5, scale = 4)
    private BigDecimal interestRate;

    // ── Lifecycle ───────────────────────────────────────────────

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "frozen_reason")
    private String frozenReason;

    @Version
    private int version;

    // ── Domain events (transient — not persisted in this entity) ─
    @Transient
    @Builder.Default
    private final List<Object> domainEvents = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (accountId == null) accountId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
        if (availableBalance == null)
            availableBalance = BigDecimal.ZERO;
        if (reservedAmount == null)
            reservedAmount = BigDecimal.ZERO;
        if (pendingCredit == null)
            pendingCredit = BigDecimal.ZERO;
        if (dailyTransactionUsed == null)
            dailyTransactionUsed = BigDecimal.ZERO;
        if (monthlyTransactionUsed == null)
            monthlyTransactionUsed = BigDecimal.ZERO;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        validateInvariants();
    }

    // ════════════════════════════════════════════════════════════
    // AGGREGATE METHODS — ALL balance modifications go here
    // ════════════════════════════════════════════════════════════

    /**
     * Reserve funds for an in-flight transfer.
     *
     * This is the core double-spending prevention method.
     * Called from ReserveBalanceCommand handler (with SELECT FOR UPDATE).
     *
     * Validates:
     * 1. Account is ACTIVE
     * 2. availableBalance >= amount
     * 3. Daily limit not exceeded
     * 4. Monthly limit not exceeded
     * 5. Amount is positive
     *
     * On success:
     * - availableBalance -= amount
     * - reservedAmount += amount
     * - Records BalanceReservedEvent
     */
    public ReservationResult reserve(BigDecimal amount, String transactionId) {
        validateActive();
        validatePositiveAmount(amount);
        validateSufficientFunds(amount);
        validateDailyLimit(amount);
        validateMonthlyLimit(amount);

        BigDecimal availableBefore = this.availableBalance;
        BigDecimal reservedBefore = this.reservedAmount;

        this.availableBalance = this.availableBalance.subtract(amount);
        this.reservedAmount = this.reservedAmount.add(amount);
        this.dailyTransactionUsed =
                this.dailyTransactionUsed.add(amount);
        this.monthlyTransactionUsed =
                this.monthlyTransactionUsed.add(amount);

        validateInvariants();

        domainEvents.add(new BalanceReservedEvent(
                accountId, transactionId, amount,
                availableBefore, this.availableBalance,
                reservedBefore, this.reservedAmount
        ));

        return new ReservationResult(
                transactionId, amount, this.availableBalance,
                this.reservedAmount);
    }

    /**
     * Release reserved funds (SAGA compensation — transfer failed).
     *
     * Funds return to availableBalance.
     * Daily/monthly limits also reversed.
     * Safe to call even on FROZEN accounts (never strand funds).
     */
    public void release(BigDecimal amount, String transactionId) {
        validatePositiveAmount(amount);

        if (this.reservedAmount.compareTo(amount) < 0) {
            throw new AccountingIntegrityException(
                    String.format(
                            "Cannot release %s — only %s is reserved. " +
                                    "accountId=%s transactionId=%s",
                            amount, this.reservedAmount,
                            accountId, transactionId));
        }

        BigDecimal availableBefore = this.availableBalance;
        BigDecimal reservedBefore = this.reservedAmount;

        this.reservedAmount = this.reservedAmount.subtract(amount);
        this.availableBalance = this.availableBalance.add(amount);

        // Reverse limit usage
        this.dailyTransactionUsed = this.dailyTransactionUsed
                .subtract(amount).max(BigDecimal.ZERO);
        this.monthlyTransactionUsed = this.monthlyTransactionUsed
                .subtract(amount).max(BigDecimal.ZERO);

        validateInvariants();

        domainEvents.add(new BalanceReleasedEvent(
                accountId, transactionId, amount,
                availableBefore, this.availableBalance,
                reservedBefore, this.reservedAmount
        ));
    }

    /**
     * Finalize a debit — funds permanently leave the account.
     *
     * Called after Ledger Service confirms double-entry records.
     * reservedAmount decreases. availableBalance does NOT increase.
     * The money is gone.
     */
    public void finalizeDebit(BigDecimal amount, String transactionId) {
        validatePositiveAmount(amount);

        if (this.reservedAmount.compareTo(amount) < 0) {
            throw new AccountingIntegrityException(
                    String.format(
                            "Cannot finalize debit %s — only %s reserved. " +
                                    "accountId=%s transactionId=%s",
                            amount, this.reservedAmount,
                            accountId, transactionId));
        }

        BigDecimal reservedBefore = this.reservedAmount;

        this.reservedAmount = this.reservedAmount.subtract(amount);
        // availableBalance unchanged — already deducted during reserve

        validateInvariants();

        domainEvents.add(new BalanceFinalizedEvent(
                accountId, transactionId, amount,
                this.availableBalance,
                reservedBefore, this.reservedAmount,
                "DEBIT"
        ));
    }

    /**
     * Credit incoming funds (receiving a transfer).
     *
     * Account must be ACTIVE to receive money.
     * Increases availableBalance immediately.
     */
    public void credit(BigDecimal amount, String transactionId,
                       String sourceAccountId) {
        validateActive();
        validatePositiveAmount(amount);

        BigDecimal availableBefore = this.availableBalance;
        this.availableBalance = this.availableBalance.add(amount);

        validateInvariants();

        domainEvents.add(new BalanceFinalizedEvent(
                accountId, transactionId, amount,
                availableBefore, this.availableBalance,
                this.reservedAmount, this.reservedAmount,
                "CREDIT"
        ));
    }

    /**
     * Freeze this account (compliance action).
     *
     * New reservations blocked. Existing reservations can still
     * be released or finalized (never strand in-flight funds).
     */
    public void freeze(String reason) {
        if (this.status == AccountStatus.FROZEN) {
            return; // Idempotent
        }
        if (this.status == AccountStatus.CLOSED) {
            throw new IllegalStateException(
                    "Cannot freeze a closed account: " + accountId);
        }
        this.status = AccountStatus.FROZEN;
        this.frozenAt = Instant.now();
        this.frozenReason = reason;
        domainEvents.add(new AccountFrozenEvent(
                accountId, reason, Instant.now()));
    }

    /**
     * Unfreeze this account (compliance action).
     */
    public void unfreeze() {
        if (this.status != AccountStatus.FROZEN) {
            return; // Idempotent
        }
        this.status = AccountStatus.ACTIVE;
        this.frozenAt = null;
        this.frozenReason = null;
    }

    /**
     * Close this account.
     *
     * Requires zero balance (both available and reserved).
     * Account cannot be reopened after closing.
     */
    public void close(String reason) {
        if (this.availableBalance.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException(
                    "Cannot close account with non-zero available balance: " +
                            this.availableBalance);
        }
        if (this.reservedAmount.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException(
                    "Cannot close account with active reservations: " +
                            this.reservedAmount);
        }
        this.status = AccountStatus.CLOSED;
        this.closedAt = Instant.now();
        domainEvents.add(new AccountClosedEvent(accountId, reason));
    }

    /**
     * Reset daily transaction limits (called by midnight scheduler).
     */
    public void resetDailyLimit() {
        this.dailyTransactionUsed = BigDecimal.ZERO;
        this.dailyResetAt = Instant.now();
    }

    /**
     * Apply monthly interest to savings accounts.
     */
    public void applyInterest() {
        if (accountType != AccountType.SAVINGS) return;
        if (interestRate.compareTo(BigDecimal.ZERO) == 0) return;

        BigDecimal monthlyRate = interestRate.divide(
                new BigDecimal("12"), 8, java.math.RoundingMode.HALF_UP);
        BigDecimal interest = availableBalance.multiply(monthlyRate)
                .setScale(4, java.math.RoundingMode.HALF_UP);

        if (interest.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal before = this.availableBalance;
            this.availableBalance = this.availableBalance.add(interest);
            domainEvents.add(new InterestAppliedEvent(
                    accountId, interest, before, this.availableBalance));
        }
    }

    // ════════════════════════════════════════════════════════════
    // DOMAIN QUERIES — Read-only state checks
    // ════════════════════════════════════════════════════════════

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean isFrozen() {
        return status == AccountStatus.FROZEN;
    }

    public boolean hasSufficientFunds(BigDecimal amount) {
        return availableBalance.compareTo(amount) >= 0;
    }

    public boolean isDailyLimitExceeded(BigDecimal amount) {
        return dailyTransactionUsed.add(amount)
                .compareTo(dailyTransactionLimit) > 0;
    }

    public boolean isMonthlyLimitExceeded(BigDecimal amount) {
        return monthlyTransactionUsed.add(amount)
                .compareTo(monthlyTransactionLimit) > 0;
    }

    public BigDecimal getDailyLimitRemaining() {
        return dailyTransactionLimit.subtract(dailyTransactionUsed);
    }

    public List<Object> pollDomainEvents() {
        List<Object> events = new ArrayList<>(this.domainEvents);
        this.domainEvents.clear();
        return events;
    }

    // ════════════════════════════════════════════════════════════
    // PRIVATE VALIDATION METHODS
    // ════════════════════════════════════════════════════════════

    private void validateActive() {
        if (status == AccountStatus.FROZEN) {
            throw new AccountFrozenException(
                    "Account is frozen: " + accountId +
                            " Reason: " + frozenReason);
        }
        if (status != AccountStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Account is not active: " + accountId +
                            " Status: " + status);
        }
    }

    private void validatePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Amount must be positive: " + amount);
        }
    }

    private void validateSufficientFunds(BigDecimal amount) {
        if (availableBalance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    String.format(
                            "Insufficient funds. Available: %s, Required: %s, " +
                                    "accountId: %s",
                            availableBalance, amount, accountId));
        }
    }

    private void validateDailyLimit(BigDecimal amount) {
        if (isDailyLimitExceeded(amount)) {
            throw new DailyLimitExceededException(
                    String.format(
                            "Daily limit exceeded. Used: %s, Limit: %s, " +
                                    "Requested: %s, accountId: %s",
                            dailyTransactionUsed, dailyTransactionLimit,
                            amount, accountId));
        }
    }

    private void validateMonthlyLimit(BigDecimal amount) {
        if (isMonthlyLimitExceeded(amount)) {
            throw new MonthlyLimitExceededException(
                    String.format(
                            "Monthly limit exceeded. Used: %s, Limit: %s, " +
                                    "Requested: %s, accountId: %s",
                            monthlyTransactionUsed, monthlyTransactionLimit,
                            amount, accountId));
        }
    }

    /**
     * Validates core accounting invariants.
     * Called after every state-changing operation.
     * The database CHECK constraints are the final backup.
     */
    private void validateInvariants() {
        if (availableBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new AccountingIntegrityException(
                    "CRITICAL: availableBalance became negative: " +
                            availableBalance + " accountId=" + accountId);
        }
        if (reservedAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new AccountingIntegrityException(
                    "CRITICAL: reservedAmount became negative: " +
                            reservedAmount + " accountId=" + accountId);
        }
    }

    // ── Inner result records ──────────────────────────────────

    public record ReservationResult(
            String transactionId,
            BigDecimal reservedAmount,
            BigDecimal newAvailableBalance,
            BigDecimal totalReserved
    ) {}
}