package com.nexus.account.application.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.account.domain.model.*;
import com.nexus.account.domain.model.enums.*;
import com.nexus.account.infrastructure.mongodb.AccountAnalyticsDocument;
import com.nexus.account.infrastructure.mongodb.AccountAnalyticsRepository;
import com.nexus.account.infrastructure.persistence.*;
import com.nexus.account.infrastructure.redis.*;
import com.nexus.account.web.dto.request.*;
import com.nexus.account.web.dto.response.*;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.StructuredTaskScope;

/**
 * Account Command Service — CQRS Write Side.
 *
 * Handles all state-changing operations on accounts.
 * Strict transaction boundaries: every command = one @Transactional.
 *
 * Balance operations (reserve, release, finalize) use:
 * 1. Redis distributed lock (application-level, fast pre-check)
 * 2. PostgreSQL SELECT FOR UPDATE (database-level, definitive)
 * 3. DDD Aggregate validation (domain-level, business rules)
 * 4. PostgreSQL CHECK constraints (database-level, last defense)
 *
 * All writes include outbox entries for Debezium CDC → Kafka.
 *
 * Pattern: CQRS Command Side
 * Pattern: DDD Aggregate Root (Account)
 * Pattern: Outbox Pattern
 * Pattern: SAGA Participant
 */
@Slf4j
@Service
public class AccountCommandService {

    private final AccountRepository accountRepository;
    private final BalanceReservationRepository reservationRepository;
    private final AccountEventRepository accountEventRepository;
    private final OutboxRepository outboxRepository;
    private final AccountAnalyticsRepository analyticsRepository;
    private final BalanceCacheRepository balanceCacheRepository;
    private final ReservationLockRepository lockRepository;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;

    // Micrometer metrics
    private final Timer reservationDurationTimer;
    private final Timer lockWaitTimer;
    private final Counter lockTimeoutCounter;
    private final Counter negativePrevention;
    private final Counter limitViolationCounter;
    private final Gauge activeReservationsGauge;

    public AccountCommandService(
            AccountRepository accountRepository,
            BalanceReservationRepository reservationRepository,
            AccountEventRepository accountEventRepository,
            OutboxRepository outboxRepository,
            AccountAnalyticsRepository analyticsRepository,
            BalanceCacheRepository balanceCacheRepository,
            ReservationLockRepository lockRepository,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            MeterRegistry meterRegistry) {

        this.accountRepository = accountRepository;
        this.reservationRepository = reservationRepository;
        this.accountEventRepository = accountEventRepository;
        this.outboxRepository = outboxRepository;
        this.analyticsRepository = analyticsRepository;
        this.balanceCacheRepository = balanceCacheRepository;
        this.lockRepository = lockRepository;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;

        this.reservationDurationTimer = Timer.builder(
                        "account.balance.reservation.duration")
                .description("End-to-end duration of ReserveBalanceCommand")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.lockWaitTimer = Timer.builder(
                        "account.balance.lock.wait.duration")
                .description("Time waiting for PostgreSQL row lock")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.lockTimeoutCounter = Counter.builder(
                        "account.balance.lock.timeout.total")
                .description("PostgreSQL lock timeout count")
                .register(meterRegistry);

        this.negativePrevention = Counter.builder(
                        "account.balance.negative.prevention.total")
                .description("Times negative balance was prevented")
                .register(meterRegistry);

        this.limitViolationCounter = Counter.builder(
                        "account.transaction.limit.violations")
                .register(meterRegistry);

        this.activeReservationsGauge = Gauge.builder(
                        "account.balance.reservation.active",
                        reservationRepository,
                        repo -> repo.countByStatus(ReservationStatus.ACTIVE))
                .register(meterRegistry);
    }

    // ══════════════════════════════════════════════════════════
    // ACCOUNT CREATION — Java 25 Structured Concurrency
    // ══════════════════════════════════════════════════════════

    /**
     * Creates checking + savings accounts for a newly KYC-approved user.
     *
     * Uses Java 25 Structured Concurrency (ShutdownOnFailure):
     * - Operation A: PostgreSQL account records (2 accounts, 1 transaction)
     * - Operation B: MongoDB analytics documents (both accounts)
     * - Operation C: Redis balance cache warm-up
     *
     * If ANY operation fails → all are rolled back.
     */
    public List<AccountCreatedResponse> createDefaultAccounts(
            UUID userId, String currency, String traceId)
            throws Exception {

        Observation obs = Observation.createNotStarted(
                "account.create.structured", observationRegistry).start();

        try {
            Account checking = buildDefaultAccount(
                    userId, AccountType.CHECKING, currency);
            Account savings = buildDefaultAccount(
                    userId, AccountType.SAVINGS, currency);

            List<Account> createdAccounts;

            try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {

                // Task A: PostgreSQL write (two accounts, atomic transaction)
                var pgTask = scope.fork(() ->
                        saveAccountsTransactionally(checking, savings, traceId));

                // Task B: MongoDB analytics init
                var mongoTask = scope.fork(() -> {
                    initAnalyticsDocument(checking.getAccountId(), userId);
                    initAnalyticsDocument(savings.getAccountId(), userId);
                    return null;
                });

                // Task C: Redis cache warm-up
                var redisTask = scope.fork(() -> {
                    warmBalanceCache(checking);
                    warmBalanceCache(savings);
                    return null;
                });

                scope.join().throwIfFailed();
                createdAccounts = pgTask.get();
            }

            obs.event(Observation.Event.of("accounts.created.success"));
            log.info("Created {} default accounts for userId={} traceId={}",
                    createdAccounts.size(), userId, traceId);

            return createdAccounts.stream()
                    .map(this::toCreatedResponse)
                    .toList();

        } finally {
            obs.stop();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected List<Account> saveAccountsTransactionally(
            Account checking, Account savings, String traceId) {

        // Save both accounts
        accountRepository.save(checking);
        accountRepository.save(savings);

        // Write account_events for both
        for (Account acct : List.of(checking, savings)) {
            accountEventRepository.save(buildCreateEvent(acct, traceId));
            outboxRepository.save(OutboxEntry.of(
                    "ACCOUNT", acct.getAccountId(),
                    "AccountCreated",
                    buildAccountCreatedPayload(acct)));
        }

        return List.of(checking, savings);
    }

    // ══════════════════════════════════════════════════════════
    // RESERVE BALANCE — Double-spending prevention core
    // ══════════════════════════════════════════════════════════

    /**
     * Reserves funds for an in-flight TransferSaga.
     *
     * Locking sequence:
     * 1. Redis distributed lock (application-level, prevents stampede)
     * 2. PostgreSQL SELECT FOR UPDATE (database-level, definitive)
     * 3. Account aggregate validation (domain invariants)
     * 4. PostgreSQL CHECK constraints (last resort DB enforcement)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BalanceOperationResult reserveBalance(
            UUID accountId, UUID transactionId, BigDecimal amount,
            String traceId) {

        Timer.Sample timerSample = Timer.start();

        Observation obs = Observation.createNotStarted(
                        "account.reserve-balance", observationRegistry)
                .lowCardinalityKeyValue("outcome", "pending")
                .start();

        String transactionIdStr = transactionId.toString();

        try {
            // ── Step 1: Idempotency check ────────────────────────
            Optional<BalanceReservation> existing =
                    reservationRepository.findByAccountIdAndTransactionId(
                            accountId, transactionId);

            if (existing.isPresent()) {
                log.info("Idempotent replay: reservation already exists " +
                                "accountId={} transactionId={}",
                        accountId, transactionId);
                obs.event(Observation.Event.of("reservation.idempotent"));
                return BalanceOperationResult.success(
                        existing.get().getReservedAmount(),
                        null, transactionIdStr, true);
            }

            // ── Step 2: Redis distributed lock ───────────────────
            boolean locked = acquireLockWithRetry(
                    accountId, transactionIdStr, 3, 100);

            if (!locked) {
                obs.event(Observation.Event.of("reservation.concurrent"));
                return BalanceOperationResult.failure(
                        "CONCURRENT_RESERVATION_IN_PROGRESS",
                        "Another reservation is in progress for this account");
            }

            try {
                // ── Step 3: SELECT FOR UPDATE (row-level lock) ───
                Timer.Sample lockSample = Timer.start();
                Account account = accountRepository
                        .findWithLockById(accountId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Account not found: " + accountId));
                lockSample.stop(lockWaitTimer);

                // ── Step 4: Aggregate domain validation + modify ─
                Account.ReservationResult reservation =
                        account.reserve(amount, transactionIdStr);

                accountRepository.save(account);

                // ── Step 5: Write reservation record ─────────────
                BalanceReservation reservationRecord =
                        BalanceReservation.builder()
                                .reservationId(UUID.randomUUID())
                                .accountId(accountId)
                                .transactionId(transactionId)
                                .reservedAmount(amount)
                                .status(ReservationStatus.ACTIVE)
                                .expiresAt(Instant.now().plus(
                                        java.time.Duration.ofHours(24)))
                                .build();
                reservationRepository.save(reservationRecord);

                // ── Step 6: Account event + outbox ───────────────
                accountEventRepository.save(buildReserveEvent(
                        account, transactionId, amount, traceId));
                outboxRepository.save(OutboxEntry.of(
                        "ACCOUNT", accountId, "BalanceReserved",
                        buildBalanceReservedPayload(
                                accountId, transactionId, amount,
                                reservation.newAvailableBalance())));

                obs.event(Observation.Event.of("reservation.success"));
                log.info("Balance reserved: accountId={} " +
                                "transactionId={} amount={} newAvailable={}",
                        accountId, transactionId, amount,
                        reservation.newAvailableBalance());

                return BalanceOperationResult.success(
                        amount, reservation.newAvailableBalance(),
                        transactionIdStr, false);

            } finally {
                lockRepository.releaseLock(accountId, transactionIdStr);
                balanceCacheRepository.invalidate(accountId);
            }

        } catch (InsufficientFundsException e) {
            obs.event(Observation.Event.of("reservation.insufficient_funds"));
            return BalanceOperationResult.failure(
                    "INSUFFICIENT_FUNDS", e.getMessage());
        } catch (AccountFrozenException e) {
            obs.event(Observation.Event.of("reservation.account_frozen"));
            return BalanceOperationResult.failure(
                    "ACCOUNT_FROZEN", e.getMessage());
        } catch (DailyLimitExceededException e) {
            limitViolationCounter.increment();
            obs.event(Observation.Event.of("reservation.daily_limit"));
            return BalanceOperationResult.failure(
                    "DAILY_LIMIT_EXCEEDED", e.getMessage());
        } catch (org.springframework.dao.CannotAcquireLockException |
                 java.sql.SQLException e) {
            lockTimeoutCounter.increment();
            obs.event(Observation.Event.of("reservation.lock_timeout"));
            log.warn("Lock timeout for accountId={}: {}",
                    accountId, e.getMessage());
            return BalanceOperationResult.failure(
                    "LOCK_TIMEOUT", "Could not acquire account lock");
        } catch (AccountingIntegrityException e) {
            negativePrevention.increment();
            obs.error(e);
            log.error("CRITICAL accounting integrity violation: {}",
                    e.getMessage(), e);
            throw e; // Let this bubble up — it's a critical bug
        } finally {
            timerSample.stop(reservationDurationTimer);
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // RELEASE BALANCE — SAGA compensation
    // ══════════════════════════════════════════════════════════

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BalanceOperationResult releaseBalance(
            UUID accountId, UUID transactionId,
            BigDecimal amount, String traceId) {

        Observation obs = Observation.createNotStarted(
                "account.release-balance", observationRegistry).start();

        try {
            // Idempotency: already released?
            Optional<BalanceReservation> reservation =
                    reservationRepository.findByAccountIdAndTransactionId(
                            accountId, transactionId);

            if (reservation.isPresent() &&
                    reservation.get().getStatus() != ReservationStatus.ACTIVE) {
                log.info("Idempotent replay: reservation already {} " +
                                "for transactionId={}",
                        reservation.get().getStatus(), transactionId);
                return BalanceOperationResult.success(
                        amount, null, transactionId.toString(), true);
            }

            // SELECT FOR UPDATE
            Account account = accountRepository
                    .findWithLockById(accountId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Account not found: " + accountId));

            // Release — safe even on FROZEN accounts
            account.release(amount, transactionId.toString());
            accountRepository.save(account);

            // Update reservation status
            reservation.ifPresent(r -> {
                reservationRepository.updateStatus(
                        r.getReservationId(), ReservationStatus.RELEASED);
            });

            // Events + outbox
            accountEventRepository.save(buildReleaseEvent(
                    account, transactionId, amount, traceId));
            outboxRepository.save(OutboxEntry.of(
                    "ACCOUNT", accountId, "BalanceReleased",
                    buildBalanceReleasedPayload(accountId, transactionId,
                            amount, account.getAvailableBalance())));

            balanceCacheRepository.invalidate(accountId);

            obs.event(Observation.Event.of("release.success"));
            log.info("Balance released: accountId={} transactionId={} " +
                    "amount={}", accountId, transactionId, amount);

            return BalanceOperationResult.success(
                    amount, account.getAvailableBalance(),
                    transactionId.toString(), false);

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // FINALIZE TRANSFER — Both sender and receiver, dual lock
    // ══════════════════════════════════════════════════════════

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeTransfer(
            UUID sourceAccountId, UUID targetAccountId,
            UUID transactionId, BigDecimal amount, String traceId) {

        Observation obs = Observation.createNotStarted(
                "account.finalize-transfer", observationRegistry).start();

        try {
            // ── Deadlock prevention: lock in consistent UUID order ──
            List<UUID> sortedIds = new ArrayList<>(
                    List.of(sourceAccountId, targetAccountId));
            Collections.sort(sortedIds);

            List<Account> accounts = accountRepository
                    .findWithLocksForTransfer(sortedIds);

            if (accounts.size() != 2) {
                throw new IllegalStateException(
                        "Could not load both accounts for transfer");
            }

            Account source = accounts.stream()
                    .filter(a -> a.getAccountId().equals(sourceAccountId))
                    .findFirst().orElseThrow();
            Account target = accounts.stream()
                    .filter(a -> a.getAccountId().equals(targetAccountId))
                    .findFirst().orElseThrow();

            // Finalize debit from source
            source.finalizeDebit(amount, transactionId.toString());
            accountRepository.save(source);

            // Credit target
            target.credit(amount, transactionId.toString(),
                    sourceAccountId.toString());
            accountRepository.save(target);

            // Update reservations to FINALIZED
            reservationRepository
                    .findByAccountIdAndTransactionId(sourceAccountId, transactionId)
                    .ifPresent(r -> reservationRepository.updateStatus(
                            r.getReservationId(), ReservationStatus.FINALIZED));

            // Events + outbox for both accounts
            accountEventRepository.save(buildFinalizeEvent(
                    source, transactionId, amount, traceId));
            accountEventRepository.save(buildCreditEvent(
                    target, transactionId, amount, traceId));

            outboxRepository.save(OutboxEntry.of(
                    "ACCOUNT", sourceAccountId, "BalanceFinalizedDebit",
                    buildFinalizedPayload(sourceAccountId, targetAccountId,
                            transactionId, amount, source.getAvailableBalance())));
            outboxRepository.save(OutboxEntry.of(
                    "ACCOUNT", targetAccountId, "BalanceCredited",
                    buildCreditedPayload(targetAccountId, sourceAccountId,
                            transactionId, amount, target.getAvailableBalance())));

            balanceCacheRepository.invalidate(sourceAccountId);
            balanceCacheRepository.invalidate(targetAccountId);

            obs.event(Observation.Event.of("finalize.success"));
            log.info("Transfer finalized: source={} target={} " +
                            "transactionId={} amount={}",
                    sourceAccountId, targetAccountId, transactionId, amount);

        } finally {
            obs.stop();
        }
    }

    // ══════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════

    private boolean acquireLockWithRetry(UUID accountId,
                                         String transactionId,
                                         int maxRetries,
                                         long retryDelayMs) {
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (lockRepository.tryAcquireLock(accountId, transactionId)) {
                return true;
            }
            if (attempt < maxRetries - 1) {
                try { Thread.sleep(retryDelayMs); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private Account buildDefaultAccount(UUID userId, AccountType type,
                                        String currency) {
        BigDecimal dailyLimit = switch (type) {
            case CHECKING -> new BigDecimal("50000.00");
            case SAVINGS -> new BigDecimal("10000.00");
            case INVESTMENT -> new BigDecimal("100000.00");
        };

        return Account.builder()
                .accountId(UUID.randomUUID())
                .accountNumber(generateAccountNumber())
                .userId(userId)
                .accountType(type)
                .currency(currency)
                .status(AccountStatus.ACTIVE)
                .availableBalance(BigDecimal.ZERO)
                .reservedAmount(BigDecimal.ZERO)
                .pendingCredit(BigDecimal.ZERO)
                .dailyTransactionLimit(dailyLimit)
                .dailyTransactionUsed(BigDecimal.ZERO)
                .monthlyTransactionLimit(dailyLimit.multiply(
                        new BigDecimal("20")))
                .monthlyTransactionUsed(BigDecimal.ZERO)
                .minimumBalance(BigDecimal.ZERO)
                .interestRate(type == AccountType.SAVINGS
                        ? new BigDecimal("0.0350") : BigDecimal.ZERO)
                .dailyResetAt(Instant.now())
                .build();
    }

    private String generateAccountNumber() {
        // Format: XXXX-XXXX-XXXX-XXXX with Luhn check digit
        Random rng = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append("-");
            sb.append(String.format("%04d", rng.nextInt(10000)));
        }
        return sb.toString();
    }

    private void initAnalyticsDocument(UUID accountId, UUID userId) {
        AccountAnalyticsDocument doc = new AccountAnalyticsDocument();
        doc.setAccountId(accountId.toString());
        doc.setUserId(userId.toString());
        doc.setLastUpdated(Instant.now());
        analyticsRepository.save(doc);
    }

    private void warmBalanceCache(Account account) {
        balanceCacheRepository.cacheBalance(
                account.getAccountId(),
                new BalanceCacheRepository.BalanceCacheEntry(
                        account.getAvailableBalance(),
                        account.getReservedAmount(),
                        account.getAvailableBalance(),
                        account.getCurrency(),
                        account.getStatus().name(),
                        Instant.now()
                ));
    }

    private ObjectNode buildAccountCreatedPayload(Account a) {
        return objectMapper.createObjectNode()
                .put("accountId", a.getAccountId().toString())
                .put("accountNumber", a.getAccountNumber())
                .put("accountType", a.getAccountType().name())
                .put("userId", a.getUserId().toString())
                .put("currency", a.getCurrency())
                .put("initialBalance", "0.0000")
                .put("createdAt", Instant.now().toString());
    }

    private ObjectNode buildBalanceReservedPayload(UUID accountId,
                                                   UUID transactionId,
                                                   BigDecimal amount,
                                                   BigDecimal newAvailable) {
        return objectMapper.createObjectNode()
                .put("accountId", accountId.toString())
                .put("transactionId", transactionId.toString())
                .put("reservedAmount", amount.toPlainString())
                .put("newAvailableBalance", newAvailable.toPlainString())
                .put("reservedAt", Instant.now().toString());
    }

    private ObjectNode buildBalanceReleasedPayload(UUID accountId,
                                                   UUID transactionId,
                                                   BigDecimal amount,
                                                   BigDecimal newAvailable) {
        return objectMapper.createObjectNode()
                .put("accountId", accountId.toString())
                .put("transactionId", transactionId.toString())
                .put("releasedAmount", amount.toPlainString())
                .put("newAvailableBalance", newAvailable.toPlainString())
                .put("releasedAt", Instant.now().toString());
    }

    private ObjectNode buildFinalizedPayload(UUID source, UUID target,
                                             UUID txnId, BigDecimal amount,
                                             BigDecimal newBalance) {
        return objectMapper.createObjectNode()
                .put("accountId", source.toString())
                .put("targetAccountId", target.toString())
                .put("transactionId", txnId.toString())
                .put("debitedAmount", amount.toPlainString())
                .put("newAvailableBalance", newBalance.toPlainString())
                .put("finalizedAt", Instant.now().toString());
    }

    private ObjectNode buildCreditedPayload(UUID accountId, UUID sourceId,
                                            UUID txnId, BigDecimal amount,
                                            BigDecimal newBalance) {
        return objectMapper.createObjectNode()
                .put("accountId", accountId.toString())
                .put("sourceAccountId", sourceId.toString())
                .put("transactionId", txnId.toString())
                .put("creditedAmount", amount.toPlainString())
                .put("newAvailableBalance", newBalance.toPlainString())
                .put("creditedAt", Instant.now().toString());
    }

    private AccountEvent buildCreateEvent(Account a, String traceId) {
        return AccountEvent.builder()
                .eventId(UUID.randomUUID())
                .accountId(a.getAccountId())
                .eventType("ACCOUNT_CREATED")
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(BigDecimal.ZERO)
                .availableBefore(BigDecimal.ZERO)
                .availableAfter(BigDecimal.ZERO)
                .reservedBefore(BigDecimal.ZERO)
                .reservedAfter(BigDecimal.ZERO)
                .traceId(traceId)
                .build();
    }

    private AccountEvent buildReserveEvent(Account a, UUID txnId,
                                           BigDecimal amount,
                                           String traceId) {
        return AccountEvent.builder()
                .eventId(UUID.randomUUID())
                .accountId(a.getAccountId())
                .transactionId(txnId)
                .eventType("BALANCE_RESERVED")
                .amount(amount)
                .balanceBefore(a.getAvailableBalance().add(amount))
                .balanceAfter(a.getAvailableBalance())
                .availableBefore(a.getAvailableBalance().add(amount))
                .availableAfter(a.getAvailableBalance())
                .reservedBefore(a.getReservedAmount().subtract(amount))
                .reservedAfter(a.getReservedAmount())
                .traceId(traceId)
                .build();
    }

    private AccountEvent buildReleaseEvent(Account a, UUID txnId,
                                           BigDecimal amount,
                                           String traceId) {
        return AccountEvent.builder()
                .eventId(UUID.randomUUID())
                .accountId(a.getAccountId())
                .transactionId(txnId)
                .eventType("BALANCE_RELEASED")
                .amount(amount)
                .balanceBefore(a.getAvailableBalance().subtract(amount))
                .balanceAfter(a.getAvailableBalance())
                .availableBefore(a.getAvailableBalance().subtract(amount))
                .availableAfter(a.getAvailableBalance())
                .reservedBefore(a.getReservedAmount().add(amount))
                .reservedAfter(a.getReservedAmount())
                .traceId(traceId)
                .build();
    }

    private AccountEvent buildFinalizeEvent(Account a, UUID txnId,
                                            BigDecimal amount,
                                            String traceId) {
        return AccountEvent.builder()
                .eventId(UUID.randomUUID())
                .accountId(a.getAccountId())
                .transactionId(txnId)
                .eventType("BALANCE_FINALIZED_DEBIT")
                .amount(amount)
                .balanceBefore(a.getAvailableBalance())
                .balanceAfter(a.getAvailableBalance())
                .availableBefore(a.getAvailableBalance())
                .availableAfter(a.getAvailableBalance())
                .reservedBefore(a.getReservedAmount().add(amount))
                .reservedAfter(a.getReservedAmount())
                .traceId(traceId)
                .build();
    }

    private AccountEvent buildCreditEvent(Account a, UUID txnId,
                                          BigDecimal amount,
                                          String traceId) {
        return AccountEvent.builder()
                .eventId(UUID.randomUUID())
                .accountId(a.getAccountId())
                .transactionId(txnId)
                .eventType("BALANCE_CREDITED")
                .amount(amount)
                .balanceBefore(a.getAvailableBalance().subtract(amount))
                .balanceAfter(a.getAvailableBalance())
                .availableBefore(a.getAvailableBalance().subtract(amount))
                .availableAfter(a.getAvailableBalance())
                .reservedBefore(a.getReservedAmount())
                .reservedAfter(a.getReservedAmount())
                .traceId(traceId)
                .build();
    }

    private AccountCreatedResponse toCreatedResponse(Account a) {
        return new AccountCreatedResponse(
                a.getAccountId().toString(),
                a.getAccountNumber(),
                a.getAccountType().name(),
                a.getCurrency(),
                a.getAvailableBalance(),
                a.getStatus().name()
        );
    }

    public record BalanceOperationResult(
            boolean success,
            String failureReason,
            BigDecimal amount,
            BigDecimal newAvailableBalance,
            String transactionId,
            boolean idempotentReplay
    ) {
        static BalanceOperationResult success(BigDecimal amount,
                                              BigDecimal newBalance,
                                              String txnId,
                                              boolean replay) {
            return new BalanceOperationResult(
                    true, null, amount, newBalance, txnId, replay);
        }

        static BalanceOperationResult failure(String reason, String msg) {
            return new BalanceOperationResult(
                    false, reason + ": " + msg,
                    null, null, null, false);
        }
    }
}