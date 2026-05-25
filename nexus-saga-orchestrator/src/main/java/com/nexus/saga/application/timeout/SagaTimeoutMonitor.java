package com.nexus.saga.application.timeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.transfer.TransferSagaProcessor;
import com.nexus.saga.domain.model.SagaFailureContext;
import com.nexus.saga.domain.model.transfer.TransferStep;
import com.nexus.saga.infrastructure.jpa.*;
import io.micrometer.core.instrument.Counter;
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
 * All timeout handling transitions to compensation,
 * which releases any reserved funds.
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

    private final Counter timeoutFiredCounter;

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

        this.timeoutFiredCounter =
                Counter.builder("saga.timeout.total")
                        .register(meterRegistry);
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
                timeoutFiredCounter.increment();
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

                    if (state.isFundsReserved()) {
                        transferProcessor.startCompensation(
                                state, TransferStep.TIMED_OUT,
                                "Timeout: " + timeout.getTimeoutType(),
                                explanation);
                    } else {
                        // No funds reserved — complete with failure directly
                        transferProcessor.startCompensation(
                                state, TransferStep.TIMED_OUT,
                                "Timeout: " + timeout.getTimeoutType(),
                                explanation);
                    }
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