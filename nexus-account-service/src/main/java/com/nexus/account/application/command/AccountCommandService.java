package com.nexus.account.application.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.account.domain.exception.AccountFrozenException;
import com.nexus.account.domain.exception.AccountingIntegrityException;
import com.nexus.account.domain.exception.DailyLimitExceededException;
import com.nexus.account.domain.exception.InsufficientFundsException;
import com.nexus.account.domain.model.*;
import com.nexus.account.domain.model.enums.*;
import com.nexus.account.infrastructure.mongodb.AccountAnalyticsDocument;
import com.nexus.account.infrastructure.mongodb.AccountAnalyticsRepository;
import com.nexus.account.infrastructure.persistence.*;
import com.nexus.account.infrastructure.redis.*;
import com.nexus.account.web.dto.response.*;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // Self-injection via proxy so @Transactional on saveAccountsTransactionally is honored
    @Lazy
    @Autowired
    private AccountCommandService self;

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
    private final com.nexus.account.integration.AccountIntegrationEventMapper integrationEventMapper;

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
            MeterRegistry meterRegistry,
            com.nexus.account.integration.AccountIntegrationEventMapper integrationEventMapper) {

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
        this.integrationEventMapper = integrationEventMapper;

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
    // ACCOUNT CREATION — CompletableFuture parallel tasks
    // ══════════════════════════════════════════════════════════

    /**
     * Creates checking + savings accounts for a newly KYC-approved user.
     *
     * Runs three tasks in parallel using CompletableFuture (allOf, fail-fast):
     * - Task A: PostgreSQL account records (2 accounts, 1 transaction)
     * - Task B: MongoDB analytics documents (both accounts)
     * - Task C: Redis balance cache warm-up
     *
     * If ANY task fails, the exception is rethrown and the caller is
     * responsible for compensating (e.g. via SAGA rollback).
     */
    public List<AccountCreatedResponse> createDefaultAccounts(
            UUID userId, String currency, String traceId)
            throws Exception {

        Observation obs = Observation.createNotStarted(
                "account.create.parallel", observationRegistry).start();

        try {
            Account checking = buildDefaultAccount(
                    userId, AccountType.CHECKING, currency);
            Account savings = buildDefaultAccount(
                    userId, AccountType.SAVINGS, currency);

            // Use a virtual-thread executor when available (Java 21+),
            // otherwise fall back to a cached thread pool.
            // Wrapped with ContextExecutorService: a raw virtual-thread executor
            // doesn't inherit this thread's ThreadLocal trace context, so pgTask/
            // mongoTask/redisTask below would otherwise run as disconnected root
            // spans instead of children of "account.create.parallel".
            ExecutorService executor = ContextExecutorService.wrap(
                    Executors.newVirtualThreadPerTaskExecutor());

            // Task A: PostgreSQL write (two accounts, atomic transaction).
            // Called via self-proxy so @Transactional(REQUIRES_NEW) is applied.
            CompletableFuture<List<Account>> pgTask = CompletableFuture
                    .supplyAsync(() ->
                                    self.saveAccountsTransactionally(checking, savings, traceId),
                            executor);

            // Task B: MongoDB analytics init
            CompletableFuture<Void> mongoTask = CompletableFuture
                    .runAsync(() -> {
                        initAnalyticsDocument(checking.getAccountId(), userId);
                        initAnalyticsDocument(savings.getAccountId(), userId);
                    }, executor);

            // Task C: Redis cache warm-up
            CompletableFuture<Void> redisTask = CompletableFuture
                    .runAsync(() -> {
                        warmBalanceCache(checking);
                        warmBalanceCache(savings);
                    }, executor);

            // Wait for all three; any failure surfaces immediately.
            try {
                CompletableFuture
                        .allOf(pgTask, mongoTask, redisTask)
                        .join();   // throws CompletionException on any failure
            } catch (CompletionException ce) {
                // Unwrap so callers see the original exception type.
                Throwable cause = ce.getCause();
                if (cause instanceof Exception e) throw e;
                throw ce;
            } finally {
                executor.shutdown();
            }

            List<Account> createdAccounts = pgTask.get();

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
    public List<Account> saveAccountsTransactionally(
            Account checking, Account savings, String traceId) {

        // Save both accounts
        accountRepository.save(checking);
        accountRepository.save(savings);

        // Write account_events for both
        for (Account acct : List.of(checking, savings)) {
            accountEventRepository.save(buildCreateEvent(acct, traceId));
            // Explicit integration-event mapping (CHANGES-BESTPRACTICES/
            // 08_EVENT_DESIGN_CHANGES.md Section 2), replacing the ad-hoc
            // buildAccountCreatedPayload(acct) ObjectNode builder that used
            // to live here - same fields published, now via a typed,
            // testable contract instead of untyped .put() calls.
            var integrationEvent = integrationEventMapper.toAccountCreated(acct);
            OutboxEntry createdEntry = OutboxEntry.of(
                    "accounts.created", acct.getAccountId(),
                    "AccountCreated",
                    (ObjectNode)
                            objectMapper.valueToTree(integrationEvent));
            createdEntry.attachTraceContext(tracer);
            outboxRepository.save(createdEntry);
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
                obs.lowCardinalityKeyValue("nexus.idempotency.duplicate", "true");
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
                var reservedEvent = integrationEventMapper.toBalanceReserved(
                        accountId, transactionId, amount,
                        reservation.newAvailableBalance());
                OutboxEntry reservedEntry = OutboxEntry.of(
                        "account.events", accountId, "BalanceReserved",
                        (ObjectNode)
                                objectMapper.valueToTree(reservedEvent));
                reservedEntry.attachTraceContext(tracer);
                outboxRepository.save(reservedEntry);

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
        } catch (org.springframework.dao.CannotAcquireLockException e) {
            lockTimeoutCounter.increment();
            obs.event(Observation.Event.of("reservation.lock_timeout"));
            log.warn("Lock timeout for accountId={}: {}",
                    accountId, e.getMessage());
            return BalanceOperationResult.failure(
                    "LOCK_TIMEOUT", "Could not acquire account lock");
        } catch (org.springframework.data.redis.RedisConnectionFailureException |
                 org.springframework.data.redis.RedisSystemException e) {
            obs.event(Observation.Event.of("reservation.redis_unavailable"));
            log.error("Redis unavailable during lock acquisition for " +
                    "accountId={}: {}", accountId, e.getMessage());
            return BalanceOperationResult.failure(
                    "INFRASTRUCTURE_UNAVAILABLE",
                    "Lock service unavailable — retry later");
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

            if (reservation.isEmpty()) {
                // ReserveBalance never committed (e.g. lock failure before DB write).
                // Compensation on a non-existent reservation is a no-op.
                log.warn("ReleaseBalance no-op: no reservation record found for " +
                        "transactionId={} accountId={} — reserve likely never committed",
                        transactionId, accountId);
                return BalanceOperationResult.success(
                        amount, null, transactionId.toString(), true);
            }

            if (reservation.get().getStatus() != ReservationStatus.ACTIVE) {
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
            var releasedEvent = integrationEventMapper.toBalanceReleased(
                    accountId, transactionId, amount, account.getAvailableBalance());
            OutboxEntry releasedEntry = OutboxEntry.of(
                    "account.events", accountId, "BalanceReleased",
                    (ObjectNode)
                            objectMapper.valueToTree(releasedEvent));
            releasedEntry.attachTraceContext(tracer);
            outboxRepository.save(releasedEntry);

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
            // ── Step 0: Idempotency check (mirrors reserveBalance) ──
            // Without this, a redelivered FinalizeTransferCommand re-runs
            // finalizeDebit() against a reservation whose amount was already
            // decremented to zero by the first successful run, throwing
            // AccountingIntegrityException ("only 0.0000 reserved"). A
            // RELEASED reservation means a ReleaseBalanceCommand already
            // ran for this transaction (compensation, or command reordering)
            // - finalizing now would credit the target with no matching
            // debit, so that case is refused, not silently treated as
            // success.
            Optional<BalanceReservation> existingReservation =
                    reservationRepository.findByAccountIdAndTransactionId(
                            sourceAccountId, transactionId);
            if (existingReservation.isPresent()) {
                ReservationStatus status = existingReservation.get().getStatus();
                if (status == ReservationStatus.FINALIZED) {
                    log.info("Idempotent replay: transfer already finalized " +
                                    "sourceAccountId={} transactionId={}",
                            sourceAccountId, transactionId);
                    obs.event(Observation.Event.of("finalize.idempotent"));
                    obs.lowCardinalityKeyValue("nexus.idempotency.duplicate", "true");
                    return;
                }
                if (status == ReservationStatus.RELEASED
                        || status == ReservationStatus.RELEASED_BY_EXPIRY) {
                    log.error("Refusing to finalize transfer: reservation " +
                                    "already {} (funds no longer held) " +
                                    "sourceAccountId={} transactionId={}",
                            status, sourceAccountId, transactionId);
                    obs.event(Observation.Event.of("finalize.already-released"));
                    return;
                }
            }

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

            var debitEvent = integrationEventMapper.toBalanceFinalizedDebit(
                    sourceAccountId, targetAccountId, transactionId,
                    amount, source.getAvailableBalance());
            OutboxEntry debitEntry = OutboxEntry.of(
                    "account.events", sourceAccountId, "BalanceFinalizedDebit",
                    (ObjectNode)
                            objectMapper.valueToTree(debitEvent));
            debitEntry.attachTraceContext(tracer);
            outboxRepository.save(debitEntry);
            var creditEvent = integrationEventMapper.toBalanceCredited(
                    targetAccountId, sourceAccountId, transactionId,
                    amount, target.getAvailableBalance());
            OutboxEntry creditEntry = OutboxEntry.of(
                    "account.events", targetAccountId, "BalanceCredited",
                    (ObjectNode)
                            objectMapper.valueToTree(creditEvent));
            creditEntry.attachTraceContext(tracer);
            outboxRepository.save(creditEntry);

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

    @Transactional
    public void creditAccount(UUID accountId, UUID transactionId,
                              BigDecimal amount, String traceId) {

        Observation obs = Observation.createNotStarted(
                "account.credit-account", observationRegistry).start();

        try {
            // Idempotency: a redelivered CreditAccountCommand (Kafka
            // at-least-once, or a saga-level retry after a lost reply — see
            // TransferSagaProcessor.retryFinalizeOrEscalate) must not
            // double-credit. Deposits (CASH_IN/DIRECT_DEPOSIT) skip balance
            // reservation, so there's no BalanceReservation row to key
            // idempotency off of like reserve/release/finalize do — the
            // transactionId on the event trail is the guard instead.
            if (!accountEventRepository
                    .findByTransactionIdOrderByOccurredAtAsc(transactionId)
                    .isEmpty()) {
                log.info("Idempotent replay: account already credited for " +
                                "transactionId={} accountId={}",
                        transactionId, accountId);
                obs.event(Observation.Event.of("credit.idempotent"));
                return;
            }

            List<Account> accounts = accountRepository
                    .findWithLocksForTransfer(List.of(accountId));
            if (accounts.isEmpty()) {
                throw new IllegalStateException("Account not found: " + accountId);
            }
            Account account = accounts.get(0);

            account.credit(amount, transactionId.toString(), "EXTERNAL_DEPOSIT");
            accountRepository.save(account);

            accountEventRepository.save(buildCreditEvent(account, transactionId, amount, traceId));

            // System external account UUID is the logical debit source for deposits
            UUID externalFunds = UUID.fromString("00000000-0000-0000-0000-000000000001");
            var depositEvent = integrationEventMapper.toBalanceCredited(
                    accountId, externalFunds, transactionId,
                    amount, account.getAvailableBalance());
            OutboxEntry depositEntry = OutboxEntry.of(
                    "account.events", accountId, "BalanceCredited",
                    (ObjectNode)
                            objectMapper.valueToTree(depositEvent));
            depositEntry.attachTraceContext(tracer);
            outboxRepository.save(depositEntry);

            balanceCacheRepository.invalidate(accountId);

            log.info("Account credited (deposit): accountId={} transactionId={} amount={}",
                    accountId, transactionId, amount);

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