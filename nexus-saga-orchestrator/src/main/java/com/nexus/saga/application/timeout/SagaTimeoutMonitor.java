package com.nexus.saga.application.timeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.transfer.TransferSagaProcessor;
import com.nexus.saga.domain.model.SagaFailureContext;
import com.nexus.saga.domain.model.transfer.TransferStep;
import com.nexus.saga.infrastructure.jpa.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Saga Timeout Monitor — polls for expired timeouts every 5 seconds.
 *
 * Compensates any saga that has exceeded its step timeout.
 * This is the safety net for cases where:
 * - A participant service never replies
 * - A participant service crashes mid-processing
 * - A network partition prevents reply delivery
 *
 * Pre-pivot timeouts (before LEDGER_POSTING) compensate by releasing the
 * balance reservation. Post-pivot timeouts (BALANCE_FINALIZE, NOTIFICATION)
 * do NOT — the ledger already posted, so they retry the idempotent
 * follow-up command or force-complete instead. See TransferSagaProcessor's
 * "POST-PIVOT TIMEOUT HANDLING" section and TransferStep's class doc.
 */
@Slf4j
@Component
public class SagaTimeoutMonitor {

    private final SagaTimeoutRepository timeoutRepository;
    private final TransferSagaRepository transferSagaRepository;
    private final OnboardingSagaRepository onboardingRepository;
    private final TransferSagaProcessor transferProcessor;
    private final com.nexus.saga.application.ai
            .SagaFailureExplainerService explainerService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SagaTimeoutMonitor(
            SagaTimeoutRepository timeoutRepository,
            TransferSagaRepository transferSagaRepository,
            OnboardingSagaRepository onboardingRepository,
            TransferSagaProcessor transferProcessor,
            com.nexus.saga.application.ai
                    .SagaFailureExplainerService explainerService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {

        this.timeoutRepository = timeoutRepository;
        this.transferSagaRepository = transferSagaRepository;
        this.onboardingRepository = onboardingRepository;
        this.transferProcessor = transferProcessor;
        this.explainerService = explainerService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Poll every 5 seconds for fired timeouts.
     * Each timeout is processed independently — failure in one
     * does not prevent processing of others.
     */
    @Scheduled(fixedDelay = 5000)
    public void checkTimeouts() {
        List<SagaTimeout> fired = timeoutRepository
                .findByFiresAtBeforeAndIsCancelledFalseAndFiredAtIsNull(
                        Instant.now());

        if (fired.isEmpty()) return;

        log.debug("Processing {} fired timeouts", fired.size());

        for (SagaTimeout timeout : fired) {
            try {
                processTimeout(timeout);
                // Tagged with sagaType to match TransferSagaProcessor's own
                // "saga.timeout.total" counter - both must share the same
                // tag-key set, or Prometheus rejects whichever registers
                // second ("all meters with the same name have the same
                // set of tag keys").
                meterRegistry.counter("saga.timeout.total",
                        "sagaType", timeout.getSagaType()).increment();
            } catch (Exception e) {
                log.error("Failed to process timeout {}: {}",
                        timeout.getTimeoutId(), e.getMessage(), e);
            }
        }
    }

    private void processTimeout(SagaTimeout timeout) {
        // Mark as fired immediately to prevent duplicate processing
        timeout.setFiredAt(Instant.now());
        timeoutRepository.save(timeout);

        if ("TRANSFER".equals(timeout.getSagaType())) {
            processTransferTimeout(timeout);
        } else if ("ONBOARDING".equals(timeout.getSagaType())) {
            processOnboardingTimeout(timeout);
        }
    }

    private void processTransferTimeout(SagaTimeout timeout) {
        transferSagaRepository.findById(timeout.getSagaId())
                .filter(s -> !s.isTerminal())
                .ifPresent(state -> {

                    log.warn("TransferSaga timeout fired: sagaId={} " +
                                    "step={} timeoutType={}",
                            timeout.getSagaId(),
                            state.getCurrentStep(),
                            timeout.getTimeoutType());

                    // BALANCE_FINALIZE and NOTIFICATION fire AFTER
                    // LEDGER_POSTING (the pivot) — releasing the balance
                    // reservation is the wrong compensation there, since
                    // the ledger already posted. Route those through
                    // pivot-aware handling instead of the generic
                    // pre-pivot compensation path below.
                    switch (timeout.getTimeoutType()) {
                        case "BALANCE_FINALIZE" -> {
                            transferProcessor.retryFinalizeOrEscalate(state);
                            return;
                        }
                        case "NOTIFICATION" -> {
                            transferProcessor
                                    .forceCompleteDespiteNotificationTimeout(state);
                            return;
                        }
                        case "COMPENSATION" -> {
                            transferProcessor.retryCompensationOrEscalate(state);
                            return;
                        }
                        default -> { /* pre-pivot — fall through below */ }
                    }

                    // Pre-pivot timeout types (BALANCE_RESERVATION,
                    // FRAUD_CHECK, FRAUD_REVIEW, LEDGER_POST): the ledger
                    // has not posted yet, so releasing the reservation is
                    // still the correct compensation.
                    var ctx = SagaFailureContext.builder()
                            .failureType(SagaFailureContext.FailureType.SAGA_TIMEOUT)
                            .userId(state.getSourceUserId().toString())
                            .amount(state.getAmount())
                            .currency(state.getCurrency())
                            .targetName(state.getTargetName())
                            .fundsWereReserved(state.isFundsReserved())
                            .fundsAreReleased(false)
                            .canRetry(true)
                            .language(state.getLanguage())
                            .build();

                    var explanation = explainerService.explain(ctx);

                    transferProcessor.startCompensation(
                            state, TransferStep.TIMED_OUT,
                            "Timeout: " + timeout.getTimeoutType(),
                            explanation);
                });
    }

    private void processOnboardingTimeout(SagaTimeout timeout) {
        onboardingRepository.findById(timeout.getSagaId())
                .filter(s -> !s.isTerminal())
                .ifPresent(state -> {
                    log.warn("OnboardingSaga timeout: sagaId={} step={}",
                            timeout.getSagaId(), state.getCurrentStep());
                    // Onboarding timeout handling delegates to
                    // OnboardingFlowSagaProcessor
                });
    }
}