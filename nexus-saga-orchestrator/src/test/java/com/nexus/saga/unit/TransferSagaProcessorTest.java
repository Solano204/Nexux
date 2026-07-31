package com.nexus.saga.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.saga.application.ai.SagaFailureExplainerService;
import com.nexus.saga.application.transfer.TransferSagaProcessor;
import com.nexus.saga.domain.exception.InvalidSagaStateException;
import com.nexus.saga.domain.model.OutboxEntry;
import com.nexus.saga.domain.model.SagaFailureExplanation;
import com.nexus.saga.domain.model.transfer.TransferSagaState;
import com.nexus.saga.domain.model.transfer.TransferStep;
import com.nexus.saga.infrastructure.jpa.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Fase 9 — saga correctness suite: happy path, one failure scenario per
 * step, the two post-pivot timeout/retry paths from Fase 2, and the two
 * duplicate-reply idempotency scenarios from Fase 7. Every assertion is on
 * the resulting DATA state (current_step, what got published, what did
 * NOT get published) — never just "no exception was thrown". That's the
 * point of this suite versus the Resilience4j tests: see the note at the
 * bottom of the class.
 *
 * Repositories are mocked (no Testcontainers) — this exercises
 * TransferSagaProcessor's own decision logic in isolation, which is where
 * every real bug found in Fases 2/7 actually lived (loadAndValidate,
 * the BALANCE_RESERVED clobber, the deposit-type retry command). A
 * Testcontainers-backed integration.TransferSagaIntegrationTest is the
 * natural next layer on top of this (real Postgres + Kafka, asserting the
 * same outcomes end-to-end) — not built here since it can't be verified
 * without running the stack, which is explicitly out of scope for this
 * session.
 */
@ExtendWith(MockitoExtension.class)
class TransferSagaProcessorTest {

    @Mock private TransferSagaRepository sagaRepository;
    @Mock private SagaStepHistoryRepository historyRepository;
    @Mock private SagaTimeoutRepository timeoutRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private SagaFailureExplainerService explainerService;
    @Mock private Tracer tracer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObservationRegistry observationRegistry = ObservationRegistry.NOOP;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private TransferSagaProcessor processor;

    @BeforeEach
    void setUp() {
        lenient().when(tracer.currentSpan()).thenReturn(null);
        lenient().when(explainerService.explain(any())).thenReturn(
                SagaFailureExplanation.fallback("TEST", true, true, "es"));
        lenient().when(sagaRepository.save(any(TransferSagaState.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        processor = new TransferSagaProcessor(
                sagaRepository, historyRepository, timeoutRepository,
                outboxRepository, explainerService, objectMapper,
                observationRegistry, tracer, meterRegistry);
    }

    // ── test fixtures ────────────────────────────────────────────

    private TransferSagaState freshState(TransferStep step) {
        return TransferSagaState.builder()
                .sagaId(UUID.randomUUID())
                .transactionId(UUID.randomUUID())
                .sourceAccountId(UUID.randomUUID())
                .targetAccountId(UUID.randomUUID())
                .sourceUserId(UUID.randomUUID())
                .amount(new BigDecimal("500.00"))
                .currency("MXN")
                .transactionType("INTERNAL_TRANSFER")
                .language("es")
                .currentStep(step)
                .fundsReserved(true)
                .fundsReleased(false)
                .compensationAttempts(0)
                .finalizeRetryCount(0)
                .startedAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private void stubFindById(TransferSagaState state) {
        when(sagaRepository.findById(state.getSagaId()))
                .thenReturn(Optional.of(state));
    }

    private ObjectNode replyFor(TransferSagaState state) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sagaId", state.getSagaId().toString());
        return node;
    }

    private OutboxEntry lastCommand() {
        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private String commandType(OutboxEntry entry) {
        return entry.getPayload().path("commandType").asText();
    }

    // ── HAPPY PATH ───────────────────────────────────────────────

    @Test
    void handleTransactionInitiated_startsSagaAndReservesBalance() {
        UUID txnId = UUID.randomUUID();
        UUID sourceAcct = UUID.randomUUID();
        UUID targetAcct = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(sagaRepository.findByTransactionId(txnId)).thenReturn(Optional.empty());
        when(sagaRepository.saveAndFlush(any(TransferSagaState.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ObjectNode event = objectMapper.createObjectNode();
        event.put("transactionId", txnId.toString());
        event.put("sourceAccountId", sourceAcct.toString());
        event.put("targetAccountId", targetAcct.toString());
        event.put("userId", userId.toString());
        event.put("amount", "500.00");
        event.put("currency", "MXN");
        event.put("transactionType", "INTERNAL_TRANSFER");

        processor.handleTransactionInitiated(event);

        OutboxEntry cmd = lastCommand();
        assertThat(commandType(cmd)).isEqualTo("ReserveBalanceCommand");
        verify(timeoutRepository).save(argThat(t -> "BALANCE_RESERVATION".equals(t.getTimeoutType())));
    }

    @Test
    void handleTransactionInitiated_isIdempotent_onDuplicateTransactionId() {
        // Fase 7: a redelivered transactions.initiated for a saga that
        // already exists must not create a second saga or publish a
        // second ReserveBalanceCommand.
        UUID txnId = UUID.randomUUID();
        when(sagaRepository.findByTransactionId(txnId))
                .thenReturn(Optional.of(freshState(TransferStep.BALANCE_RESERVING)));

        ObjectNode event = objectMapper.createObjectNode();
        event.put("transactionId", txnId.toString());
        event.put("sourceAccountId", UUID.randomUUID().toString());
        event.put("userId", UUID.randomUUID().toString());
        event.put("amount", "500.00");

        processor.handleTransactionInitiated(event);

        verifyNoInteractions(outboxRepository);
        verify(sagaRepository, never()).saveAndFlush(any());
    }

    @Test
    void handleBalanceReserved_transitionsToFraudChecking_notBalanceReserved() {
        // Regression test for the Fase 7 fix: currentStep must persist as
        // FRAUD_CHECKING (what was actually dispatched), not get clobbered
        // back to BALANCE_RESERVED.
        TransferSagaState state = freshState(TransferStep.BALANCE_RESERVING);
        stubFindById(state);

        ObjectNode reply = replyFor(state);
        reply.put("reservationId", UUID.randomUUID().toString());
        reply.put("newAvailableBalance", "100.00");

        processor.handleBalanceReserved(reply);

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.FRAUD_CHECKING);
        assertThat(commandType(lastCommand())).isEqualTo("CheckFraudCommand");
    }

    @Test
    void handleBalanceReserved_onRedeliveredReply_isRejectedAsStale_andDoesNotRepublish() {
        // Fase 7's core regression test: the saga already advanced past
        // BALANCE_RESERVING (e.g. the first delivery of this exact reply
        // already processed). A second delivery must be rejected, not
        // silently reprocessed into a duplicate CheckFraudCommand.
        TransferSagaState state = freshState(TransferStep.FRAUD_CHECKING);
        stubFindById(state);

        ObjectNode reply = replyFor(state);
        reply.put("reservationId", UUID.randomUUID().toString());
        reply.put("newAvailableBalance", "100.00");

        assertThatThrownBy(() -> processor.handleBalanceReserved(reply))
                .isInstanceOf(InvalidSagaStateException.class);

        verifyNoInteractions(outboxRepository);
    }

    @Test
    void handleFraudCleared_transitionsToLedgerPosting() {
        TransferSagaState state = freshState(TransferStep.FRAUD_CHECKING);
        stubFindById(state);

        ObjectNode reply = replyFor(state);
        reply.put("riskScore", "12.5");

        processor.handleFraudCleared(reply);

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.LEDGER_POSTING);
        assertThat(commandType(lastCommand())).isEqualTo("PostLedgerCommand");
    }

    @Test
    void handleLedgerPosted_transitionsToBalanceFinalizing_setsLedgerIds() {
        TransferSagaState state = freshState(TransferStep.LEDGER_POSTING);
        stubFindById(state);

        UUID postingId = UUID.randomUUID();
        ObjectNode reply = replyFor(state);
        reply.put("postingId", postingId.toString());
        reply.put("debitEntryId", UUID.randomUUID().toString());
        reply.put("creditEntryId", UUID.randomUUID().toString());

        processor.handleLedgerPosted(reply);

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.BALANCE_FINALIZING);
        assertThat(state.getLedgerPostingId()).isEqualTo(postingId);
        assertThat(commandType(lastCommand())).isEqualTo("FinalizeTransferCommand");
    }

    @Test
    void handleBalanceFinalized_transitionsToNotificationSending() {
        TransferSagaState state = freshState(TransferStep.BALANCE_FINALIZING);
        stubFindById(state);

        processor.handleBalanceFinalized(replyFor(state));

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.NOTIFICATION_SENDING);
        assertThat(commandType(lastCommand())).isEqualTo("SendTransactionNotificationCommand");
        verify(timeoutRepository).save(argThat(t -> "NOTIFICATION".equals(t.getTimeoutType())));
    }

    @Test
    void handleNotificationSent_completesSaga() {
        TransferSagaState state = freshState(TransferStep.NOTIFICATION_SENDING);
        when(sagaRepository.findById(state.getSagaId())).thenReturn(Optional.of(state));

        processor.handleNotificationSent(replyFor(state));

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.COMPLETED);
        assertThat(state.getCompletedAt()).isNotNull();
        OutboxEntry event = lastCommand();
        assertThat(event.getTopic()).isEqualTo("transactions.saga.completed");
    }

    // ── FAILURE AT EACH STEP ─────────────────────────────────────

    @Test
    void handleBalanceReservationFailed_completesAsCompensated_withoutReleasingBalance() {
        // Funds were never reserved, so there is nothing to release —
        // must go straight to COMPENSATION_COMPLETED, not through
        // RELEASING_BALANCE.
        TransferSagaState state = freshState(TransferStep.BALANCE_RESERVING);
        state.setFundsReserved(false);
        stubFindById(state);

        ObjectNode reply = replyFor(state);
        reply.set("payload", objectMapper.createObjectNode().put("reason", "INSUFFICIENT_FUNDS"));

        processor.handleBalanceReservationFailed(reply);

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.COMPENSATION_COMPLETED);
        assertThat(state.isFundsReleased()).isFalse();
        assertThat(commandType(lastCommand())).isEqualTo("SendTransactionFailureNotificationCommand");
    }

    @Test
    void handleFraudRejected_startsCompensation_publishesReleaseBalanceCommand() {
        TransferSagaState state = freshState(TransferStep.FRAUD_CHECKING);
        stubFindById(state);

        ObjectNode reply = replyFor(state);
        reply.put("riskScore", "97.0");

        processor.handleFraudRejected(reply);

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.RELEASING_BALANCE);
        assertThat(commandType(lastCommand())).isEqualTo("ReleaseBalanceCommand");
        assertThat(state.getCompensationAttempts()).isEqualTo(1);
    }

    @Test
    void handleLedgerFailed_startsCompensation_ledgerNeverPosted() {
        TransferSagaState state = freshState(TransferStep.LEDGER_POSTING);
        stubFindById(state);

        processor.handleLedgerFailed(replyFor(state));

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.RELEASING_BALANCE);
        assertThat(commandType(lastCommand())).isEqualTo("ReleaseBalanceCommand");
    }

    @Test
    void handleBalanceReleased_completesCompensation_marksFundsReleased() {
        TransferSagaState state = freshState(TransferStep.RELEASING_BALANCE);
        when(sagaRepository.findById(state.getSagaId())).thenReturn(Optional.of(state));

        processor.handleBalanceReleased(replyFor(state));

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.COMPENSATION_COMPLETED);
        assertThat(state.isFundsReleased()).isTrue();
        assertThat(state.getCompletedAt()).isNotNull();
    }

    // ── POST-PIVOT TIMEOUT / RETRY (Fase 2) ──────────────────────

    @Test
    void retryFinalizeOrEscalate_retries_republishesFinalizeTransferCommand() {
        TransferSagaState state = freshState(TransferStep.BALANCE_FINALIZING);

        processor.retryFinalizeOrEscalate(state);

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.BALANCE_FINALIZING);
        assertThat(state.getFinalizeRetryCount()).isEqualTo(1);
        assertThat(commandType(lastCommand())).isEqualTo("FinalizeTransferCommand");
        // Never the pre-pivot compensation — the ledger already posted.
        verify(outboxRepository, never()).save(argThat(e ->
                "ReleaseBalanceCommand".equals(commandType(e))));
    }

    @Test
    void retryFinalizeOrEscalate_forDepositType_republishesCreditAccountCommand() {
        // Regression test: deposits (CASH_IN/DIRECT_DEPOSIT) were finalized
        // via CreditAccountCommand, not FinalizeTransferCommand — the retry
        // must send the same command type the original dispatch used.
        TransferSagaState state = freshState(TransferStep.BALANCE_FINALIZING);
        state.setTransactionType("DIRECT_DEPOSIT");

        processor.retryFinalizeOrEscalate(state);

        assertThat(commandType(lastCommand())).isEqualTo("CreditAccountCommand");
    }

    @Test
    void retryFinalizeOrEscalate_exhausted_escalatesToPermanentlyFailed_withoutReleasingBalance() {
        TransferSagaState state = freshState(TransferStep.BALANCE_FINALIZING);
        state.setFinalizeRetryCount(5); // already at the cap

        processor.retryFinalizeOrEscalate(state);

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.PERMANENTLY_FAILED);
        assertThat(state.getCompletedAt()).isNotNull();
        // Escalation publishes no further command — a human decides next,
        // it never falls back to releasing the (already-posted) ledger amount.
        verifyNoInteractions(outboxRepository);
    }

    @Test
    void forceCompleteDespiteNotificationTimeout_completesSagaWithoutAReply() {
        TransferSagaState state = freshState(TransferStep.NOTIFICATION_SENDING);

        processor.forceCompleteDespiteNotificationTimeout(state);

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.COMPLETED);
        // Must never compensate here — money already moved before this step.
        verify(outboxRepository, never()).save(argThat(e ->
                "ReleaseBalanceCommand".equals(commandType(e))));
    }

    @Test
    void retryCompensationOrEscalate_exhausted_escalatesToPermanentlyFailed() {
        TransferSagaState state = freshState(TransferStep.RELEASING_BALANCE);
        state.setCompensationAttempts(5); // already at the cap

        processor.retryCompensationOrEscalate(state);

        assertThat(state.getCurrentStep()).isEqualTo(TransferStep.PERMANENTLY_FAILED);
        assertThat(state.getCompletedAt()).isNotNull();
    }

    /*
     * Fase 9, point 3 — why this suite is a different kind of test than the
     * Resilience4j guide's:
     *
     * The Resilience4j tests ask "does the SYSTEM stay up?" — trip a
     * circuit breaker, force a timeout, and assert the caller gets a
     * fallback response instead of hanging or crashing. That's an
     * availability assertion: the service kept serving traffic.
     *
     * This suite asks a different question: after a fault, is the DATA in a
     * state that's correct by the business's own rules? "The saga didn't
     * crash" is not good enough here — a saga that stays perfectly "up"
     * while silently re-publishing a duplicate CheckFraudCommand, or
     * releasing a reservation for a transfer whose ledger entry already
     * posted, is a passing availability test and a real money bug at the
     * same time (both bugs fixed in this session were exactly that: no
     * exception, no outage, just wrong data). Every assertion above checks
     * currentStep, what got published, and what did NOT get published —
     * business-state correctness, not uptime.
     */
}
