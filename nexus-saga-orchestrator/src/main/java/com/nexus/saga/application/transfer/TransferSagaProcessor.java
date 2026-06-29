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
        this.sagaDurationTimer =
                Timer.builder("saga.duration.seconds")
                        .tag("sagaType", "TRANSFER")
                        .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                        .register(meterRegistry);
    }

    // ══════════════════════════════════════════════════════════
    // STEP 1 — Transaction Initiated
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void handleTransactionInitiated(JsonNode event) {
        UUID transactionId = UUID.fromString(
                event.path("transactionId").asText());

        // Idempotency guard: unique constraint prevents duplicates
        try {
            TransferSagaState state = TransferSagaState.builder()
                    .sagaId(UUID.randomUUID())
                    .transactionId(transactionId)
                    .sourceAccountId(UUID.fromString(
                            event.path("sourceAccountId").asText()))
                    .targetAccountId(UUID.fromString(
                            event.path("targetAccountId").asText()))
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

            state = sagaRepository.save(state);
            sagaStartedCounter.increment();

            // Transition and publish ReserveBalanceCommand
            transitionAndPublish(state,
                    TransferStep.BALANCE_RESERVING,
                    "Initiating balance reservation",
                    buildReserveBalanceCommand(state));

            scheduleTimeout(state.getSagaId(), "TRANSFER",
                    "BALANCE_RESERVATION", Duration.ofSeconds(30));

        } catch (DataIntegrityViolationException e) {
            // Duplicate event — already processing this transaction
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

        transitionAndPublish(state,
                TransferStep.FRAUD_CHECKING,
                "Balance reserved — initiating fraud check",
                buildCheckFraudCommand(state));

        state.setCurrentStep(TransferStep.BALANCE_RESERVED);
        appendHistory(state, TransferStep.BALANCE_RESERVING,
                TransferStep.BALANCE_RESERVED, "Balance reserved");

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
                sagaId, TransferStep.FRAUD_CHECKING, TransferStep.BALANCE_RESERVED);

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
                sagaId, TransferStep.FRAUD_CHECKING, TransferStep.BALANCE_RESERVED);

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

        transitionAndPublish(state,
                TransferStep.BALANCE_FINALIZING,
                "Ledger posted — finalizing balances",
                buildFinalizeTransferCommand(state));

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

        state.setCompletedAt(Instant.now());
        appendHistory(state, TransferStep.NOTIFICATION_SENDING,
                TransferStep.COMPLETED, "Saga completed successfully");
        state.setCurrentStep(TransferStep.COMPLETED);
        sagaRepository.save(state);

        outboxRepository.save(OutboxEntry.forDomainEvent(
                "transactions.saga.completed",
                sagaId.toString(),
                Map.of(
                        "transactionId", state.getTransactionId().toString(),
                        "sagaId", sagaId.toString(),
                        "status", "COMPLETED",
                        "completedAt", Instant.now().toString()),
                objectMapper));

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
    // COMPENSATION
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void startCompensation(TransferSagaState state,
                                  TransferStep failureStep,
                                  String reason,
                                  SagaFailureExplanation explanation) {

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

        state.setFundsReleased(true);
        state.setCompletedAt(Instant.now());

        appendHistory(state, TransferStep.RELEASING_BALANCE,
                TransferStep.COMPENSATION_COMPLETED,
                "Balance released — compensation complete");
        state.setCurrentStep(TransferStep.COMPENSATION_COMPLETED);
        sagaRepository.save(state);

        // Update failure explanation with confirmed fund release
        var updatedExplanation = state.getFailureExplanation();

        // Publish failure notification
        outboxRepository.save(OutboxEntry.forSagaCommand(
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
                objectMapper));

        // Publish saga failed event
        outboxRepository.save(OutboxEntry.forDomainEvent(
                "transactions.saga.failed",
                sagaId.toString(),
                Map.of(
                        "transactionId",
                        state.getTransactionId().toString(),
                        "failureType", state.getFailureType(),
                        "fundsReleased", true),
                objectMapper));

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

        state.setFailureReason(reason);
        state.setFailureExplanation(explanation);
        state.setFundsReleased(fundsReleased);
        state.setCompletedAt(Instant.now());

        appendHistory(state, state.getCurrentStep(),
                TransferStep.COMPENSATION_COMPLETED, reason);
        state.setCurrentStep(TransferStep.COMPENSATION_COMPLETED);
        sagaRepository.save(state);

        // Publish failure notification command
        outboxRepository.save(OutboxEntry.forSagaCommand(
                "saga.commands", state.getSagaId().toString(),
                buildFailureNotificationCommand(state, explanation),
                objectMapper));

        sagaCompensatedCounter.increment();
    }

    // ══════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════

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
            log.warn("Unexpected saga state: sagaId={} " +
                            "expected={} actual={}",
                    sagaId, java.util.Arrays.toString(expected),
                    state.getCurrentStep());

            if (state.isTerminal()) {
                throw new InvalidSagaStateException(
                        "Saga already terminal: " + sagaId);
            }
        }
        return state;
    }

    @Transactional
    protected void transitionAndPublish(TransferSagaState state,
                                        TransferStep nextStep,
                                        String reason,
                                        Object command) {
        appendHistory(state, state.getCurrentStep(),
                nextStep, reason);
        state.setCurrentStep(nextStep);

        // Atomic: state + outbox in same transaction
        sagaRepository.save(state);
        outboxRepository.save(OutboxEntry.forSagaCommand(
                "saga.commands",
                state.getSagaId().toString(),
                command, objectMapper));
    }

    private void appendHistory(TransferSagaState state,
                               TransferStep from,
                               TransferStep to,
                               String reason) {
        SagaStepHistory history = SagaStepHistory.builder()
                .sagaId(state.getSagaId())
                .sagaType("TRANSFER")
                .fromStep(from.name())
                .toStep(to.name())
                .reason(reason)
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
        payload.put("sourceUserId", state.getSourceUserId().toString());
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