package com.nexus.saga.application.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.ai.SagaFailureExplainerService;
import com.nexus.saga.domain.exception.InvalidSagaStateException;
import com.nexus.saga.domain.exception.SagaNotFoundException;
import com.nexus.saga.domain.model.*;
import com.nexus.saga.domain.model.transfer.*;
import com.nexus.saga.infrastructure.jpa.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Transfer Saga Processor — complete TransferSaga state machine.
 *
 * All methods are @Transactional:
 * - State transition + outbox entry commit atomically
 * - Prevents partial state where command is published but
 *   state was not updated (or vice versa)
 *
 * Optimistic locking (@Version) prevents concurrent state corruption
 * when two Kafka consumers process replies for the same saga.
 *
 * Pattern: SAGA Orchestration with Outbox for reliable messaging
 */
@Slf4j
@Service
public class TransferSagaProcessor {

    // Post-pivot retry caps (Rule 1: compensation/retry must never just
    // "fail and continue" — either it eventually succeeds, or it escalates
    // to PERMANENTLY_FAILED for manual intervention, never a silent drop).
    private static final int MAX_FINALIZE_RETRIES = 5;
    private static final int MAX_COMPENSATION_RETRIES = 5;

    private final TransferSagaRepository sagaRepository;
    private final SagaStepHistoryRepository historyRepository;
    private final SagaTimeoutRepository timeoutRepository;
    private final OutboxRepository outboxRepository;
    private final SagaFailureExplainerService explainerService;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;

    private final Counter sagaStartedCounter;
    private final Counter sagaCompletedCounter;
    private final Counter sagaCompensatedCounter;
    private final Counter sagaTimeoutCounter;
    private final Counter sagaPermanentlyFailedCounter;
    private final Counter sagaCompletedDegradedCounter;
    private final Timer sagaDurationTimer;

    public TransferSagaProcessor(
            TransferSagaRepository sagaRepository,
            SagaStepHistoryRepository historyRepository,
            SagaTimeoutRepository timeoutRepository,
            OutboxRepository outboxRepository,
            SagaFailureExplainerService explainerService,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            MeterRegistry meterRegistry) {

        this.sagaRepository = sagaRepository;
        this.historyRepository = historyRepository;
        this.timeoutRepository = timeoutRepository;
        this.outboxRepository = outboxRepository;
        this.explainerService = explainerService;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;

        this.sagaStartedCounter =
                Counter.builder("saga.started.total")
                        .tag("sagaType", "TRANSFER").register(meterRegistry);
        this.sagaCompletedCounter =
                Counter.builder("saga.completed.total")
                        .tag("sagaType", "TRANSFER")
                        .tag("outcome", "SUCCESS").register(meterRegistry);
        this.sagaCompensatedCounter =
                Counter.builder("saga.completed.total")
                        .tag("sagaType", "TRANSFER")
                        .tag("outcome", "COMPENSATION_COMPLETED")
                        .register(meterRegistry);
        this.sagaTimeoutCounter =
                Counter.builder("saga.timeout.total")
                        .tag("sagaType", "TRANSFER").register(meterRegistry);
        this.sagaPermanentlyFailedCounter =
                Counter.builder("saga.completed.total")
                        .tag("sagaType", "TRANSFER")
                        .tag("outcome", "PERMANENTLY_FAILED")
                        .register(meterRegistry);
        this.sagaCompletedDegradedCounter =
                Counter.builder("saga.completed.total")
                        .tag("sagaType", "TRANSFER")
                        .tag("outcome", "COMPLETED_DEGRADED_NOTIFICATION")
                        .register(meterRegistry);
        this.sagaDurationTimer =
                Timer.builder("saga.duration.seconds")
                        .tag("sagaType", "TRANSFER")
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry);

        // Fase 10: "how many sagas are in step X right now" existed only as
        // an on-demand REST call (InternalSagaController.getStats()), not
        // as a Prometheus-scrapable metric. One gauge per non-terminal
        // step, same name + a `step` tag, so Grafana can chart them
        // together with `sum by (step) (saga_in_progress)`. Weak-reference
        // safe: sagaRepository is a Spring singleton the registry's weak
        // ref won't outlive, same as account-service's
        // activeReservationsGauge.
        for (TransferStep step : java.util.List.of(
                TransferStep.BALANCE_RESERVING, TransferStep.FRAUD_CHECKING,
                TransferStep.FRAUD_REVIEW, TransferStep.LEDGER_POSTING,
                TransferStep.BALANCE_FINALIZING, TransferStep.NOTIFICATION_SENDING,
                TransferStep.RELEASING_BALANCE)) {
            Gauge.builder("saga.in_progress", sagaRepository,
                            repo -> (double) repo.countByCurrentStepIn(
                                    java.util.List.of(step)))
                    .tag("sagaType", "TRANSFER")
                    .tag("step", step.name())
                    .register(meterRegistry);
        }
    }

    // ══════════════════════════════════════════════════════════
    // STEP 1 — Transaction Initiated
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void handleTransactionInitiated(JsonNode event) {
        UUID transactionId = UUID.fromString(
                event.path("transactionId").asText());

        // Idempotency guard: check existence BEFORE inserting. The previous
        // approach (try/catch around save() for DataIntegrityViolationException)
        // did not actually work - Hibernate defers the INSERT flush to
        // @Transactional commit time, which happens AFTER this method (and
        // its try/catch) returns, so the constraint violation propagated as
        // an unhandled exception instead of being caught here. This is the
        // real mechanism behind the "duplicate key value violates unique
        // constraint" errors in the logs, on top of Kafka redelivery
        // (see KafkaConfig: this consumer previously had no bounded backoff).
        if (sagaRepository.findByTransactionId(transactionId).isPresent()) {
            Span dupSpan = tracer.currentSpan();
            if (dupSpan != null) {
                dupSpan.tag("nexus.idempotency.duplicate", "true");
                dupSpan.tag("nexus.saga.transaction.id", transactionId.toString());
            }
            log.debug("Duplicate TransactionInitiated for txnId={} — " +
                    "saga already exists, treating as replay", transactionId);
            return;
        }

        try {
            TransferSagaState state = TransferSagaState.builder()
                    .sagaId(UUID.randomUUID())
                    .transactionId(transactionId)
                    .sourceAccountId(UUID.fromString(
                            event.path("sourceAccountId").asText()))
                    .targetAccountId(event.path("targetAccountId").isNull() || event.path("targetAccountId").isMissingNode()
                            ? null : UUID.fromString(event.path("targetAccountId").asText()))
                    .sourceUserId(UUID.fromString(
                            event.path("userId").asText()))
                    .targetUserId(event.has("targetUserId")
                            ? UUID.fromString(
                            event.path("targetUserId").asText()) : null)
                    .amount(new BigDecimal(
                            event.path("amount").asText()))
                    .currency(event.path("currency").asText("MXN"))
                    .transactionType(
                            event.path("transactionType").asText())
                    .description(event.path("description").asText(""))
                    .merchantName(
                            event.path("merchantName").asText(null))
                    .targetName(
                            event.path("targetName").asText(""))
                    .language(event.path("language").asText("es"))
                    .currentStep(TransferStep.STARTED)
                    .fundsReserved(false)
                    .fundsReleased(false)
                    .compensationAttempts(0)
                    .build();

            // saveAndFlush (not save): forces the INSERT - and any unique
            // constraint violation - to happen HERE, synchronously, so the
            // catch block below can actually catch it. A plain save() defers
            // the flush to transaction commit, after this try/catch exits.
            state = sagaRepository.saveAndFlush(state);
            sagaStartedCounter.increment();

            if (isDepositType(state)) {
                // Deposits skip balance reservation and fraud check
                transitionAndPublish(state,
                        TransferStep.LEDGER_POSTING,
                        "Direct deposit — posting to ledger",
                        buildDepositLedgerCommand(state));
                scheduleTimeout(state.getSagaId(), "TRANSFER",
                        "LEDGER_POST", Duration.ofSeconds(30));
            } else {
                transitionAndPublish(state,
                        TransferStep.BALANCE_RESERVING,
                        "Initiating balance reservation",
                        buildReserveBalanceCommand(state));
                scheduleTimeout(state.getSagaId(), "TRANSFER",
                        "BALANCE_RESERVATION", Duration.ofSeconds(30));
            }

        } catch (DataIntegrityViolationException e) {
            // Duplicate event — already processing this transaction
            Span dupSpan = tracer.currentSpan();
            if (dupSpan != null) {
                dupSpan.tag("nexus.idempotency.duplicate", "true");
                dupSpan.tag("nexus.saga.transaction.id", transactionId.toString());
            }
            log.debug("Duplicate TransactionInitiated for txnId={}" +
                    " — skipping", transactionId);
        }
    }

    // ══════════════════════════════════════════════════════════
    // STEP 2 — Balance Reserved
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void handleBalanceReserved(JsonNode reply) {
        UUID sagaId = UUID.fromString(
                reply.path("sagaId").asText());

        TransferSagaState state = loadAndValidate(
                sagaId, TransferStep.BALANCE_RESERVING);

        cancelTimeout(sagaId, "BALANCE_RESERVATION");

        String reservationIdStr = reply.path("reservationId").asText("");
        if (!reservationIdStr.isEmpty()) {
            state.setReservationId(UUID.fromString(reservationIdStr));
        }
        JsonNode payload = reply.path("payload");
        String balanceStr = payload.path("newAvailableBalance").asText(
                reply.path("newAvailableBalance").asText("0"));
        state.setNewAvailableBalance(new BigDecimal(balanceStr));
        state.setFundsReserved(true);

        // transitionAndPublish() below sets currentStep=FRAUD_CHECKING and
        // persists it. A previous version of this method then overwrote
        // state.setCurrentStep(BALANCE_RESERVED) immediately afterward —
        // since `state` stays managed by JPA for the rest of this
        // @Transactional method, that last write is what actually got
        // flushed to the DB at commit, not FRAUD_CHECKING. The saga's
        // *persisted* current_step silently disagreed with the step that
        // had actually been dispatched (CheckFraudCommand was already
        // published under FRAUD_CHECKING). handleFraudCleared/
        // handleFraudRejected were written to accept BOTH steps as a
        // workaround — which weakens loadAndValidate exactly where it
        // matters most: a genuinely duplicate/out-of-order FraudClearedReply
        // could slip through unnoticed with two valid "current" states to
        // match instead of one. Fixed by not clobbering it; see
        // loadAndValidate below for the other half of this fix.
        transitionAndPublish(state,
                TransferStep.FRAUD_CHECKING,
                "Balance reserved — initiating fraud check",
                buildCheckFraudCommand(state));

        scheduleTimeout(state.getSagaId(), "TRANSFER",
                "FRAUD_CHECK", Duration.ofSeconds(60));
    }

    @Transactional
    public void handleBalanceReservationFailed(JsonNode reply) {
        UUID sagaId = UUID.fromString(
                reply.path("sagaId").asText());

        TransferSagaState state = loadAndValidate(
                sagaId, TransferStep.BALANCE_RESERVING);

        cancelTimeout(sagaId, "BALANCE_RESERVATION");

        String reason = reply.path("payload").path("reason").asText(
                reply.path("reason").asText("UNKNOWN"));

        SagaFailureContext ctx = SagaFailureContext.builder()
                .failureType(SagaFailureContext.FailureType.INSUFFICIENT_FUNDS)
                .userId(state.getSourceUserId().toString())
                .amount(state.getAmount())
                .currency(state.getCurrency())
                .targetName(state.getTargetName())
                .fundsWereReserved(false)
                .fundsAreReleased(false)
                .canRetry(true)
                .language(state.getLanguage())
                .build();

        SagaFailureExplanation explanation = explainerService.explain(ctx);
        completeWithFailure(state,
                TransferStep.BALANCE_RESERVATION_FAILED,
                reason, explanation, false);
    }

    // ══════════════════════════════════════════════════════════
    // STEP 3 — Fraud Decision
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void handleFraudCleared(JsonNode reply) {
        UUID sagaId = UUID.fromString(
                reply.path("sagaId").asText());

        TransferSagaState state = loadAndValidate(
                sagaId, TransferStep.FRAUD_CHECKING);

        cancelTimeout(sagaId, "FRAUD_CHECK");

        state.setFraudScore(
                new BigDecimal(reply.path("riskScore").asText("0")));
        state.setFraudDecision("CLEARED");

        appendHistory(state, TransferStep.FRAUD_CHECKING,
                TransferStep.FRAUD_CLEARED, "Fraud cleared");
        state.setCurrentStep(TransferStep.FRAUD_CLEARED);

        transitionAndPublish(state,
                TransferStep.LEDGER_POSTING,
                "Fraud cleared — initiating ledger posting",
                buildPostLedgerCommand(state));

        scheduleTimeout(state.getSagaId(), "TRANSFER",
                "LEDGER_POST", Duration.ofSeconds(30));
    }

    @Transactional
    public void handleFraudRejected(JsonNode reply) {
        UUID sagaId = UUID.fromString(
                reply.path("sagaId").asText());

        TransferSagaState state = loadAndValidate(
                sagaId, TransferStep.FRAUD_CHECKING);

        cancelTimeout(sagaId, "FRAUD_CHECK");

        state.setFraudScore(
                new BigDecimal(reply.path("riskScore").asText("100")));
        state.setFraudDecision("REJECTED");

        SagaFailureContext ctx = SagaFailureContext.builder()
                .failureType(SagaFailureContext.FailureType.FRAUD_REJECTED)
                .userId(state.getSourceUserId().toString())
                .amount(state.getAmount())
                .currency(state.getCurrency())
                .targetName(state.getTargetName())
                .fundsWereReserved(true)
                .fundsAreReleased(false)    // not yet released
                .canRetry(true)
                .language(state.getLanguage())
                .build();

        SagaFailureExplanation explanation = explainerService.explain(ctx);
        startCompensation(state, TransferStep.FRAUD_REJECTED,
                "Fraud rejected: score=" + state.getFraudScore(),
                explanation);
    }

    @Transactional
    public void handleFraudReview(JsonNode reply) {
        UUID sagaId = UUID.fromString(
                reply.path("sagaId").asText());

        TransferSagaState state = loadAndValidate(
                sagaId, TransferStep.FRAUD_CHECKING);

        cancelTimeout(sagaId, "FRAUD_CHECK");

        String reviewIdStr = reply.path("reviewId").asText("");
        if (!reviewIdStr.isEmpty()) {
            state.setReviewId(UUID.fromString(reviewIdStr));
        }
        state.setReviewPriority(
                reply.path("reviewPriority").asText("MEDIUM"));

        tagSagaSpan(sagaId, TransferStep.FRAUD_REVIEW);
        appendHistory(state, TransferStep.FRAUD_CHECKING,
                TransferStep.FRAUD_REVIEW, "Flagged for manual review");
        state.setCurrentStep(TransferStep.FRAUD_REVIEW);
        sagaRepository.save(state);

        // Extended timeout for manual review
        scheduleTimeout(state.getSagaId(), "TRANSFER",
                "FRAUD_REVIEW", Duration.ofHours(4));

        log.info("Transfer paused for fraud review: sagaId={} " +
                        "reviewId={} priority={}",
                sagaId, state.getReviewId(), state.getReviewPriority());
    }

    // ══════════════════════════════════════════════════════════
    // STEP 4 — Ledger Posted
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void handleLedgerPosted(JsonNode reply) {
        UUID sagaId = UUID.fromString(
                reply.path("sagaId").asText());

        TransferSagaState state = loadAndValidate(
                sagaId, TransferStep.LEDGER_POSTING);

        cancelTimeout(sagaId, "LEDGER_POST");

        state.setLedgerPostingId(UUID.fromString(
                reply.path("postingId").asText()));
        state.setDebitEntryId(UUID.fromString(
                reply.path("debitEntryId").asText()));
        state.setCreditEntryId(UUID.fromString(
                reply.path("creditEntryId").asText()));

        appendHistory(state, TransferStep.LEDGER_POSTING,
                TransferStep.LEDGER_POSTED, "Ledger posted");
        state.setCurrentStep(TransferStep.LEDGER_POSTED);

        if (isDepositType(state)) {
            // Deposits have no reserved balance to finalize — just credit the account
            transitionAndPublish(state,
                    TransferStep.BALANCE_FINALIZING,
                    "Deposit posted to ledger — crediting account",
                    buildCreditAccountCommand(state));
        } else {
            transitionAndPublish(state,
                    TransferStep.BALANCE_FINALIZING,
                    "Ledger posted — finalizing balances",
                    buildFinalizeTransferCommand(state));
        }

        scheduleTimeout(state.getSagaId(), "TRANSFER",
                "BALANCE_FINALIZE", Duration.ofSeconds(30));
    }

    @Transactional
    public void handleLedgerFailed(JsonNode reply) {
        UUID sagaId = UUID.fromString(
                reply.path("sagaId").asText());

        TransferSagaState state = loadAndValidate(
                sagaId, TransferStep.LEDGER_POSTING);

        cancelTimeout(sagaId, "LEDGER_POST");

        SagaFailureContext ctx = SagaFailureContext.builder()
                .failureType(SagaFailureContext.FailureType.SAGA_TIMEOUT)
                .userId(state.getSourceUserId().toString())
                .amount(state.getAmount())
                .currency(state.getCurrency())
                .fundsWereReserved(true)
                .fundsAreReleased(false)
                .canRetry(true)
                .language(state.getLanguage())
                .build();

        SagaFailureExplanation explanation = explainerService.explain(ctx);
        startCompensation(state, TransferStep.LEDGER_FAILED,
                "Ledger posting failed", explanation);
    }

    // ══════════════════════════════════════════════════════════
    // STEP 5 — Balance Finalized
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void handleBalanceFinalized(JsonNode reply) {
        UUID sagaId = UUID.fromString(
                reply.path("sagaId").asText());

        TransferSagaState state = loadAndValidate(
                sagaId, TransferStep.BALANCE_FINALIZING);

        cancelTimeout(sagaId, "BALANCE_FINALIZE");

        appendHistory(state, TransferStep.BALANCE_FINALIZING,
                TransferStep.BALANCE_FINALIZED, "Balances finalized");
        state.setCurrentStep(TransferStep.BALANCE_FINALIZED);

        transitionAndPublish(state,
                TransferStep.NOTIFICATION_SENDING,
                "Sending transaction notifications",
                buildSendNotificationCommand(state));

        // Notification failure does NOT trigger compensation
        // — money has moved, notification is best-effort
        scheduleTimeout(state.getSagaId(), "TRANSFER",
                "NOTIFICATION", Duration.ofMinutes(5));
    }

    // ══════════════════════════════════════════════════════════
    // STEP 6 — Completed
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void handleNotificationSent(JsonNode reply) {
        UUID sagaId = UUID.fromString(
                reply.path("sagaId").asText());

        TransferSagaState state = sagaRepository
                .findById(sagaId)
                .filter(s -> s.getCurrentStep() ==
                        TransferStep.NOTIFICATION_SENDING
                        || s.getCurrentStep() ==
                        TransferStep.BALANCE_FINALIZED)
                .orElse(null);

        if (state == null) return;

        cancelTimeout(sagaId, "NOTIFICATION");

        tagSagaSpan(sagaId, TransferStep.COMPLETED);
        state.setCompletedAt(Instant.now());
        appendHistory(state, TransferStep.NOTIFICATION_SENDING,
                TransferStep.COMPLETED, "Saga completed successfully");
        state.setCurrentStep(TransferStep.COMPLETED);
        sagaRepository.save(state);

        OutboxEntry completedEntry = OutboxEntry.forDomainEvent(
                "transactions.saga.completed",
                sagaId.toString(),
                Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "sagaId", sagaId.toString(),
                        "status", "COMPLETED",
                        "completedAt", Instant.now().toString()),
                objectMapper);
        completedEntry.attachTraceContext(tracer);
        outboxRepository.save(completedEntry);

        long durationMs = Duration.between(
                state.getStartedAt(), state.getCompletedAt()).toMillis();

        sagaDurationTimer.record(durationMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        sagaCompletedCounter.increment();

        log.info("TransferSaga COMPLETED: sagaId={} txnId={} " +
                        "amount={} {} in {}ms",
                sagaId, state.getTransactionId(),
                state.getAmount(), state.getCurrency(), durationMs);
    }

    // ══════════════════════════════════════════════════════════
    // POST-PIVOT TIMEOUT HANDLING — called only from SagaTimeoutMonitor.
    //
    // LEDGER_POSTING already happened by the time a saga can be sitting in
    // BALANCE_FINALIZING or NOTIFICATION_SENDING, so the pre-pivot
    // compensation (ReleaseBalanceCommand, below) is the wrong action here —
    // it would release a reservation that no longer corresponds to what the
    // ledger says happened. See TransferStep's class doc.
    // ══════════════════════════════════════════════════════════

    /**
     * BALANCE_FINALIZE timeout: retry, don't compensate.
     * account-service's FinalizeTransferCommand handler is idempotent and
     * reservation-status-aware (ACTIVE → finalizes, FINALIZED → no-ops,
     * RELEASED → refuses) — so a missing reply almost always means the
     * reply was lost, not that the operation failed. Redelivering the same
     * command is safe. Only after exhausting retries do we treat this as a
     * real failure requiring a human (not "release the funds" — the ledger
     * already posted, so undoing it now is a business decision, not an
     * automatic one).
     */
    @Transactional
    public void retryFinalizeOrEscalate(TransferSagaState state) {
        if (state.isTerminal()) return;

        int attempt = state.getFinalizeRetryCount() + 1;
        state.setFinalizeRetryCount(attempt);

        if (attempt > MAX_FINALIZE_RETRIES) {
            tagSagaSpan(state.getSagaId(), TransferStep.PERMANENTLY_FAILED);
            appendHistory(state, state.getCurrentStep(),
                    TransferStep.PERMANENTLY_FAILED,
                    "BALANCE_FINALIZE unconfirmed after " +
                            MAX_FINALIZE_RETRIES + " retries");
            state.setCurrentStep(TransferStep.PERMANENTLY_FAILED);
            state.setFailureReason("Balance finalization did not confirm " +
                    "after " + MAX_FINALIZE_RETRIES + " retries — ledger " +
                    "posting " + state.getLedgerPostingId() + " needs " +
                    "manual reconciliation");
            state.setFailureType("BALANCE_FINALIZE_EXHAUSTED");
            state.setCompletedAt(Instant.now());
            sagaRepository.save(state);
            recordSagaDuration(state);

            sagaPermanentlyFailedCounter.increment();
            log.error("TransferSaga PERMANENTLY_FAILED: sagaId={} txnId={} " +
                    "ledgerPostingId={} — ledger already posted; confirm " +
                    "manually whether account-service finalized, or reverse " +
                    "via nexus-ledger-service's admin REVERSAL endpoint " +
                    "if the transfer must be undone",
                    state.getSagaId(), state.getTransactionId(),
                    state.getLedgerPostingId());
            return;
        }

        log.warn("BALANCE_FINALIZE timeout — retry {}/{}: sagaId={} txnId={}",
                attempt, MAX_FINALIZE_RETRIES, state.getSagaId(),
                state.getTransactionId());

        // Deposits (CASH_IN/DIRECT_DEPOSIT) were finalized via
        // CreditAccountCommand, not FinalizeTransferCommand — retry the
        // same command handleLedgerPosted originally sent, or account-service
        // never sees the retry it's actually waiting on.
        Object retryCommand = isDepositType(state)
                ? buildCreditAccountCommand(state)
                : buildFinalizeTransferCommand(state);

        transitionAndPublish(state, TransferStep.BALANCE_FINALIZING,
                "Retrying finalize, attempt " + attempt,
                retryCommand);

        scheduleTimeout(state.getSagaId(), "TRANSFER",
                "BALANCE_FINALIZE", backoff(attempt));
    }

    /**
     * NOTIFICATION timeout: complete anyway, never compensate.
     * By the time this fires, BALANCE_FINALIZED already happened — the
     * transfer is done. A slow/failed notification is a delivery problem,
     * not a saga failure (matches the intent already stated in
     * handleBalanceFinalized's comment, which the timeout path previously
     * didn't honor).
     */
    @Transactional
    public void forceCompleteDespiteNotificationTimeout(
            TransferSagaState state) {
        if (state.isTerminal()) return;

        log.warn("NOTIFICATION timeout — completing saga anyway, funds " +
                "already moved: sagaId={} txnId={}",
                state.getSagaId(), state.getTransactionId());

        tagSagaSpan(state.getSagaId(), TransferStep.COMPLETED);
        state.setCompletedAt(Instant.now());
        appendHistory(state, TransferStep.NOTIFICATION_SENDING,
                TransferStep.COMPLETED,
                "Completed despite notification timeout — delivery status " +
                        "unknown, money already moved");
        state.setCurrentStep(TransferStep.COMPLETED);
        sagaRepository.save(state);

        OutboxEntry completedEntry = OutboxEntry.forDomainEvent(
                "transactions.saga.completed",
                state.getSagaId().toString(),
                Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "sagaId", state.getSagaId().toString(),
                        "status", "COMPLETED",
                        "notificationStatus", "TIMED_OUT",
                        "completedAt", Instant.now().toString()),
                objectMapper);
        completedEntry.attachTraceContext(tracer);
        outboxRepository.save(completedEntry);
        recordSagaDuration(state);

        sagaCompletedDegradedCounter.increment();
        sagaCompletedCounter.increment();
    }

    /**
     * COMPENSATION timeout: retry the (pre-pivot) balance release itself
     * with backoff, then escalate. This is Rule 1 applied to the
     * compensation action — RELEASING_BALANCE → PERMANENTLY_FAILED was
     * already a valid transition in the enum but nothing ever triggered it;
     * previously a stuck release retried forever on a fixed 30s cadence
     * with no cap and no alert.
     */
    @Transactional
    public void retryCompensationOrEscalate(TransferSagaState state) {
        if (state.isTerminal()) return;

        int attempt = state.getCompensationAttempts() + 1;
        state.setCompensationAttempts(attempt);

        if (attempt > MAX_COMPENSATION_RETRIES) {
            tagSagaSpan(state.getSagaId(), TransferStep.PERMANENTLY_FAILED);
            appendHistory(state, TransferStep.RELEASING_BALANCE,
                    TransferStep.PERMANENTLY_FAILED,
                    "ReleaseBalanceCommand unconfirmed after " +
                            MAX_COMPENSATION_RETRIES + " attempts");
            state.setCurrentStep(TransferStep.PERMANENTLY_FAILED);
            state.setFailureType("COMPENSATION_EXHAUSTED");
            state.setCompletedAt(Instant.now());
            sagaRepository.save(state);
            recordSagaDuration(state);

            sagaPermanentlyFailedCounter.increment();
            log.error("TransferSaga compensation PERMANENTLY_FAILED — " +
                    "manual fund release required: sagaId={} txnId={} " +
                    "sourceAccountId={} amount={} {}",
                    state.getSagaId(), state.getTransactionId(),
                    state.getSourceAccountId(), state.getAmount(),
                    state.getCurrency());
            return;
        }

        log.warn("Compensation (ReleaseBalanceCommand) timeout — retry " +
                "{}/{}: sagaId={}", attempt, MAX_COMPENSATION_RETRIES,
                state.getSagaId());

        transitionAndPublish(state, TransferStep.RELEASING_BALANCE,
                "Retrying balance release, attempt " + attempt,
                buildReleaseBalanceCommand(state));

        scheduleTimeout(state.getSagaId(), "TRANSFER",
                "COMPENSATION", backoff(attempt));
    }

    /** Exponential backoff, capped at 10 minutes — retry cadence for
     * post-pivot finalize/compensation retries. Base 30s matches the
     * original fixed BALANCE_FINALIZE/COMPENSATION timeout. */
    private Duration backoff(int attempt) {
        long seconds = 30L * (1L << Math.min(attempt, 5));
        return Duration.ofSeconds(Math.min(seconds, 600));
    }

    // ══════════════════════════════════════════════════════════
    // COMPENSATION
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void startCompensation(TransferSagaState state,
                                  TransferStep failureStep,
                                  String reason,
                                  SagaFailureExplanation explanation) {

        tagSagaSpan(state.getSagaId(), failureStep);
        state.setFailureReason(reason);
        state.setFailureExplanation(explanation);
        state.setFailureType(failureStep.name());

        appendHistory(state, state.getCurrentStep(),
                failureStep, reason);
        state.setCurrentStep(failureStep);

        transitionAndPublish(state,
                TransferStep.RELEASING_BALANCE,
                "Releasing balance reservation",
                buildReleaseBalanceCommand(state));

        scheduleTimeout(state.getSagaId(), "TRANSFER",
                "COMPENSATION", Duration.ofSeconds(30));

        state.setCompensationAttempts(
                state.getCompensationAttempts() + 1);
        sagaRepository.save(state);
    }

    @Transactional
    public void handleBalanceReleased(JsonNode reply) {
        UUID sagaId = UUID.fromString(
                reply.path("sagaId").asText());

        TransferSagaState state = sagaRepository
                .findById(sagaId)
                .orElseThrow(() ->
                        new SagaNotFoundException(sagaId.toString()));

        cancelTimeout(sagaId, "COMPENSATION");

        tagSagaSpan(sagaId, TransferStep.COMPENSATION_COMPLETED);
        state.setFundsReleased(true);
        state.setCompletedAt(Instant.now());

        appendHistory(state, TransferStep.RELEASING_BALANCE,
                TransferStep.COMPENSATION_COMPLETED,
                "Balance released — compensation complete");
        state.setCurrentStep(TransferStep.COMPENSATION_COMPLETED);
        sagaRepository.save(state);

        // Update failure explanation with confirmed fund release - falls
        // back to a minimal explanation if this saga somehow reached
        // compensation-complete without one (e.g. explainerService never
        // ran), since Map.of() below would otherwise NPE on a null value
        // and take down the failure notification with it - exactly the
        // wrong place to silently stop informing the user.
        var updatedExplanation = state.getFailureExplanation();
        String failureType = state.getFailureType();
        if (failureType == null) failureType = "UNKNOWN";
        if (updatedExplanation == null) {
            updatedExplanation = SagaFailureExplanation.fallback(
                    failureType, true, false, state.getLanguage());
        }

        // Publish failure notification
        OutboxEntry failureNotifEntry = OutboxEntry.forSagaCommand(
                "saga.commands", sagaId.toString(),
                Map.of(
                        "commandType",
                        "SendTransactionFailureNotificationCommand",
                        "targetService", "nexus-notification-service",
                        "sagaId", sagaId.toString(),
                        "payload", Map.of(
                                "transactionId",
                                state.getTransactionId().toString(),
                                "userId", state.getSourceUserId().toString(),
                                "amount", state.getAmount().toPlainString(),
                                "currency", state.getCurrency(),
                                "explanation", updatedExplanation,
                                "fundsReleased", true)),
                objectMapper);
        failureNotifEntry.attachTraceContext(tracer);
        outboxRepository.save(failureNotifEntry);

        // Publish saga failed event
        OutboxEntry sagaFailedEntry = OutboxEntry.forDomainEvent(
                "transactions.saga.failed",
                sagaId.toString(),
                Map.of(
                        "transactionId",
                        state.getTransactionId().toString(),
                        "failureType", failureType,
                        "fundsReleased", true),
                objectMapper);
        sagaFailedEntry.attachTraceContext(tracer);
        outboxRepository.save(sagaFailedEntry);

        sagaCompensatedCounter.increment();
        sagaDurationTimer.record(
                Duration.between(state.getStartedAt(),
                        state.getCompletedAt()).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void completeWithFailure(TransferSagaState state,
                                     TransferStep failureStep,
                                     String reason,
                                     SagaFailureExplanation explanation,
                                     boolean fundsReleased) {

        tagSagaSpan(state.getSagaId(), failureStep);
        state.setFailureReason(reason);
        state.setFailureExplanation(explanation);
        state.setFundsReleased(fundsReleased);
        state.setCompletedAt(Instant.now());

        appendHistory(state, state.getCurrentStep(),
                TransferStep.COMPENSATION_COMPLETED, reason);
        state.setCurrentStep(TransferStep.COMPENSATION_COMPLETED);
        sagaRepository.save(state);
        recordSagaDuration(state);

        // Publish failure notification command
        OutboxEntry failureCmdEntry = OutboxEntry.forSagaCommand(
                "saga.commands", state.getSagaId().toString(),
                buildFailureNotificationCommand(state, explanation),
                objectMapper);
        failureCmdEntry.attachTraceContext(tracer);
        outboxRepository.save(failureCmdEntry);

        sagaCompensatedCounter.increment();
    }

    /**
     * Fase 10: every path that reaches a terminal step must feed
     * saga.duration.seconds, or the p50/p90/p95/p99 percentiles silently
     * exclude whichever failure modes forget to call this — historically
     * that was BALANCE_RESERVATION_FAILED (pre-existing) plus every
     * PERMANENTLY_FAILED/degraded-completion path added in Fase 2, which
     * would have meant the metric only ever reflected the happy path and
     * the pre-pivot compensation path, silently excluding exactly the
     * slowest, worst-case sagas.
     */
    private void recordSagaDuration(TransferSagaState state) {
        if (state.getStartedAt() == null || state.getCompletedAt() == null) return;
        sagaDurationTimer.record(
                Duration.between(state.getStartedAt(), state.getCompletedAt()).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ══════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════

    /**
     * Idempotency guard for saga replies. A reply is only processed if the
     * saga is still sitting in the step that reply answers — any mismatch
     * (terminal OR not) means this reply has already been consumed by an
     * earlier delivery, or arrived out of order after something else (a
     * timeout, a different reply) already moved the saga on. Either way,
     * re-running the handler body would republish a command / re-apply an
     * effect that's already in flight or already done — Kafka at-least-once
     * plus this saga's own retry loops (Fase 2) make redelivery routine,
     * not an edge case.
     *
     * Previously this only threw when the saga was terminal and otherwise
     * logged-and-continued for any non-terminal mismatch — which is the
     * far more common case (a redelivered reply after the saga already
     * advanced to the next step) and was NOT actually guarded: the handler
     * ran again and republished its outbound command. See
     * handleBalanceReserved's comment for the concrete bug this caused.
     */
    private TransferSagaState loadAndValidate(UUID sagaId,
                                              TransferStep... expected) {
        TransferSagaState state = sagaRepository
                .findById(sagaId)
                .orElseThrow(() ->
                        new SagaNotFoundException(sagaId.toString()));

        boolean matches = false;
        for (TransferStep e : expected) {
            if (state.getCurrentStep() == e) { matches = true; break; }
        }
        if (!matches) {
            log.warn("Stale/out-of-order saga reply, skipping: sagaId={} " +
                            "expected={} actual={}",
                    sagaId, java.util.Arrays.toString(expected),
                    state.getCurrentStep());
            throw new InvalidSagaStateException(
                    "Saga sagaId=" + sagaId + " is at " +
                            state.getCurrentStep() + ", not " +
                            java.util.Arrays.toString(expected) +
                            " — stale or duplicate reply");
        }
        return state;
    }

    @Transactional
    protected void transitionAndPublish(TransferSagaState state,
                                        TransferStep nextStep,
                                        String reason,
                                        Object command) {
        // Fail-open by design: this table was previously defined but never
        // consulted at runtime (dead code), so treating a mismatch as fatal
        // here could stall a real saga on a gap in the table itself. Log
        // loudly instead so an unexpected transition is visible (metrics/
        // alerting on this log line is a Phase 10 follow-up), and fix the
        // table when one shows up instead of guessing every case up front.
        if (!state.getCurrentStep().canTransitionTo(nextStep)) {
            log.warn("Saga transition not in canTransitionTo() table — " +
                    "proceeding anyway, flagged for review: sagaId={} {} -> {}",
                    state.getSagaId(), state.getCurrentStep(), nextStep);
        }
        tagSagaSpan(state.getSagaId(), nextStep);
        appendHistory(state, state.getCurrentStep(),
                nextStep, reason);
        state.setCurrentStep(nextStep);

        // Atomic: state + outbox in same transaction
        sagaRepository.save(state);
        OutboxEntry cmdEntry = OutboxEntry.forSagaCommand(
                "saga.commands",
                state.getSagaId().toString(),
                command, objectMapper);
        cmdEntry.attachTraceContext(tracer);
        outboxRepository.save(cmdEntry);
    }

    private void appendHistory(TransferSagaState state,
                               TransferStep from,
                               TransferStep to,
                               String reason) {
        // state.getLastUpdatedAt() still holds the PREVIOUS transition's
        // timestamp here — @PreUpdate only bumps it once sagaRepository.save()
        // runs, which happens after this call in transitionAndPublish(). So
        // this is genuinely "time spent in `from`", not a self-referential
        // measurement.
        Integer durationMs = state.getLastUpdatedAt() != null
                ? (int) Math.min(Integer.MAX_VALUE,
                        Duration.between(state.getLastUpdatedAt(), Instant.now())
                                .toMillis())
                : null;

        SagaStepHistory history = SagaStepHistory.builder()
                .sagaId(state.getSagaId())
                .sagaType("TRANSFER")
                .fromStep(from.name())
                .toStep(to.name())
                .reason(reason)
                .durationMs(durationMs)
                .traceId(getTraceId())
                .build();
        historyRepository.save(history);
    }

    private void scheduleTimeout(UUID sagaId, String sagaType,
                                 String timeoutType,
                                 Duration duration) {
        SagaTimeout timeout = SagaTimeout.builder()
                .sagaId(sagaId)
                .sagaType(sagaType)
                .timeoutType(timeoutType)
                .firesAt(Instant.now().plus(duration))
                .isCancelled(false)
                .build();
        timeoutRepository.save(timeout);
    }

    private void cancelTimeout(UUID sagaId, String timeoutType) {
        List<SagaTimeout> pending = timeoutRepository
                .findBySagaIdAndTimeoutTypeAndIsCancelledFalse(
                        sagaId, timeoutType);
        if (!pending.isEmpty()) {
            pending.forEach(t -> t.setCancelled(true));
            timeoutRepository.saveAll(pending);
        }
    }

    private String getTraceId() {
        return tracer.currentSpan() != null
                ? tracer.currentSpan().context().traceId()
                : "no-trace";
    }

    /**
     * Tags the currently active span (opened by the Kafka consumer via
     * KafkaTracePropagation) with sagaId/step so a saga can be searched
     * end-to-end in Zipkin by nexus.saga.id, and a given step's traces
     * filtered by nexus.saga.step - without this, sagaId only existed in
     * logs and the DB, not as a queryable span tag.
     */
    private void tagSagaSpan(UUID sagaId, TransferStep step) {
        Span span = tracer.currentSpan();
        if (span == null) return;
        span.tag("nexus.saga.id", sagaId.toString());
        span.tag("nexus.saga.step", step.name());
    }

    // ── Command builders ──────────────────────────────────────

    private Map<String, Object> buildReserveBalanceCommand(
            TransferSagaState state) {
        return Map.of(
                "commandType", "ReserveBalanceCommand",
                "targetService", "nexus-account-service",
                "sagaId", state.getSagaId().toString(),
                "payload", Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "accountId", state.getSourceAccountId().toString(),
                        "amount", state.getAmount().toPlainString(),
                        "currency", state.getCurrency()));
    }

    private Map<String, Object> buildCheckFraudCommand(
            TransferSagaState state) {
        return Map.of(
                "commandType", "CheckFraudCommand",
                "targetService", "nexus-fraud-service",
                "sagaId", state.getSagaId().toString(),
                "payload", Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "sourceAccountId", state.getSourceAccountId().toString(),
                        "targetAccountId", state.getTargetAccountId().toString(),
                        "sourceUserId", state.getSourceUserId().toString(),
                        "amount", state.getAmount().toPlainString(),
                        "currency", state.getCurrency(),
                        "transactionType", state.getTransactionType()));
    }

    private Map<String, Object> buildPostLedgerCommand(
            TransferSagaState state) {
        return Map.of(
                "commandType", "PostLedgerCommand",
                "targetService", "nexus-ledger-service",
                "sagaId", state.getSagaId().toString(),
                "payload", Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "sourceAccountId", state.getSourceAccountId().toString(),
                        "targetAccountId", state.getTargetAccountId().toString(),
                        "amount", state.getAmount().toPlainString(),
                        "currency", state.getCurrency(),
                        "postingType", "TRANSFER",
                        "description", state.getDescription() != null
                                ? state.getDescription() : "Transfer"));
    }

    private Map<String, Object> buildFinalizeTransferCommand(
            TransferSagaState state) {
        return Map.of(
                "commandType", "FinalizeTransferCommand",
                "targetService", "nexus-account-service",
                "sagaId", state.getSagaId().toString(),
                "payload", Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "sourceAccountId", state.getSourceAccountId().toString(),
                        "targetAccountId", state.getTargetAccountId().toString(),
                        "amount", state.getAmount().toPlainString(),
                        "currency", state.getCurrency(),
                        "reservationId", state.getReservationId() != null
                                ? state.getReservationId().toString() : ""));
    }

    private Map<String, Object> buildReleaseBalanceCommand(
            TransferSagaState state) {
        return Map.of(
                "commandType", "ReleaseBalanceCommand",
                "targetService", "nexus-account-service",
                "sagaId", state.getSagaId().toString(),
                "payload", Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "accountId", state.getSourceAccountId().toString(),
                        "amount", state.getAmount().toPlainString(),
                        "currency", state.getCurrency(),
                        "reservationId", state.getReservationId() != null
                                ? state.getReservationId().toString() : "none",
                        "reason", "SAGA_COMPENSATION"));
    }

    private Map<String, Object> buildSendNotificationCommand(
            TransferSagaState state) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("transactionId", state.getTransactionId().toString());
        // "userId", not "sourceUserId" - SagaCommandConsumer (the single
        // shared consumer for every *NotificationCommand type, in
        // nexus-notification-service) reads payload.userId uniformly
        // regardless of command type. SendWelcomeNotificationCommand/
        // SendAccountCreatedNotificationCommand already used this key
        // correctly; this one didn't, so every transaction notification
        // silently delivered with an empty userId (confirmed live via
        // docker logs: "Notification delivered: channel=IN_APP userId=").
        payload.put("userId", state.getSourceUserId().toString());
        payload.put("amount", state.getAmount().toPlainString());
        payload.put("currency", state.getCurrency());
        if (state.getTargetUserId() != null) {
            payload.put("targetUserId", state.getTargetUserId().toString());
        }
        return Map.of(
                "commandType", "SendTransactionNotificationCommand",
                "targetService", "nexus-notification-service",
                "sagaId", state.getSagaId().toString(),
                "payload", payload);
    }

    private boolean isDepositType(TransferSagaState state) {
        String type = state.getTransactionType();
        return "DIRECT_DEPOSIT".equals(type) || "CASH_IN".equals(type);
    }

    private Map<String, Object> buildDepositLedgerCommand(
            TransferSagaState state) {
        return Map.of(
                "commandType", "PostLedgerCommand",
                "targetService", "nexus-ledger-service",
                "sagaId", state.getSagaId().toString(),
                "payload", Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "targetAccountId", state.getSourceAccountId().toString(),
                        "amount", state.getAmount().toPlainString(),
                        "currency", state.getCurrency(),
                        "postingType", state.getTransactionType(),
                        "description", state.getDescription() != null
                                ? state.getDescription() : "Direct Deposit"));
    }

    private Map<String, Object> buildCreditAccountCommand(
            TransferSagaState state) {
        return Map.of(
                "commandType", "CreditAccountCommand",
                "targetService", "nexus-account-service",
                "sagaId", state.getSagaId().toString(),
                "payload", Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "accountId", state.getSourceAccountId().toString(),
                        "amount", state.getAmount().toPlainString(),
                        "currency", state.getCurrency()));
    }

    private Map<String, Object> buildFailureNotificationCommand(
            TransferSagaState state,
            SagaFailureExplanation explanation) {
        return Map.of(
                "commandType",
                "SendTransactionFailureNotificationCommand",
                "targetService", "nexus-notification-service",
                "sagaId", state.getSagaId().toString(),
                "payload", Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "userId", state.getSourceUserId().toString(),
                        "amount", state.getAmount().toPlainString(),
                        "currency", state.getCurrency(),
                        "userFacingTitle", explanation.userFacingTitle(),
                        "userFacingExplanation",
                        explanation.userFacingExplanation(),
                        "fundsReleased", explanation.fundsAreReleased(),
                        "canRetry", explanation.canRetry()));
    }
}