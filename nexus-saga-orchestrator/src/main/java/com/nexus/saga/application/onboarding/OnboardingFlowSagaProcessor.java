package com.nexus.saga.application.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.ai.SagaFailureExplainerService;
import com.nexus.saga.domain.model.*;
import com.nexus.saga.domain.model.onboarding.*;
import com.nexus.saga.infrastructure.jpa.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/**
 * OnboardingFlowSaga Processor.
 *
 * State machine: STARTED -> KYC_INITIATED -> KYC_APPROVED ->
 * ACCOUNTS_CREATING -> ACCOUNTS_CREATED -> WELCOME_NOTIFICATION_SENT -> COMPLETED
 *
 * Failure: KYC_REJECTED -> COMPENSATING_REGISTRATION -> REGISTRATION_CANCELLED
 */
@Slf4j
@Service
public class OnboardingFlowSagaProcessor {

    private final OnboardingSagaRepository sagaRepository;
    private final SagaStepHistoryRepository historyRepository;
    private final SagaTimeoutRepository timeoutRepository;
    private final OutboxRepository outboxRepository;
    private final SagaFailureExplainerService explainerService;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Counter sagaStartedCounter;
    private final Counter sagaCompletedCounter;

    public OnboardingFlowSagaProcessor(
            OnboardingSagaRepository sagaRepository,
            SagaStepHistoryRepository historyRepository,
            SagaTimeoutRepository timeoutRepository,
            OutboxRepository outboxRepository,
            SagaFailureExplainerService explainerService,
            ObjectMapper objectMapper,
            Tracer tracer,
            MeterRegistry meterRegistry) {
        this.sagaRepository = sagaRepository;
        this.historyRepository = historyRepository;
        this.timeoutRepository = timeoutRepository;
        this.outboxRepository = outboxRepository;
        this.explainerService = explainerService;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.sagaStartedCounter = Counter.builder("saga.started.total")
                .tag("sagaType", "ONBOARDING").register(meterRegistry);
        this.sagaCompletedCounter = Counter.builder("saga.completed.total")
                .tag("sagaType", "ONBOARDING")
                .tag("outcome", "SUCCESS").register(meterRegistry);
    }

    /**
     * Tags the currently active span (opened by the Kafka consumer) with
     * sagaId/step so a saga can be searched end-to-end in Zipkin by
     * nexus.saga.id, and a given step's traces filtered by nexus.saga.step.
     */
    private void tagSagaSpan(UUID sagaId, OnboardingStep step) {
        Span span = tracer.currentSpan();
        if (span == null) return;
        span.tag("nexus.saga.id", sagaId.toString());
        span.tag("nexus.saga.step", step.name());
    }

    @Transactional
    public void handleUserRegistered(JsonNode event) {
        UUID userId = UUID.fromString(event.path("userId").asText());
        if (sagaRepository.findByUserIdAndCompletedAtIsNull(userId).isPresent()) {
            Span dupSpan = tracer.currentSpan();
            if (dupSpan != null) {
                dupSpan.tag("nexus.idempotency.duplicate", "true");
            }
            log.debug("Onboarding saga already exists for userId={}", userId);
            return;
        }

        OnboardingFlowSagaState state = OnboardingFlowSagaState.builder()
                .sagaId(UUID.randomUUID())
                .userId(userId)
                .email(event.path("email").asText(""))
                .currentStep(OnboardingStep.KYC_INITIATED)
                .attemptCount(0)
                .language(event.path("language").asText("es"))
                .build();

        sagaRepository.save(state);
        tagSagaSpan(state.getSagaId(), OnboardingStep.KYC_INITIATED);
        sagaStartedCounter.increment();
        log.info("OnboardingFlowSaga started: sagaId={} userId={}", state.getSagaId(), userId);
    }

    @Transactional
    public void handleKycApproved(JsonNode event) {
        UUID userId = UUID.fromString(event.path("userId").asText());
        sagaRepository.findByUserIdAndCompletedAtIsNull(userId).ifPresent(state -> {
            // KYC_REJECTED is reachable here too: the dual Rekognition/OpenAI KYC
            // paths (see AWS-DOCKER-WORKFLOWS/01_REGISTRATION_FLOW.md) can call back
            // twice for the same verification, and identity-service's last-write-wins
            // decision can arrive as approved after this saga already recorded a
            // canRetry=true rejection. Recover instead of dropping it, or the account
            // never gets created even though the user's real KYC status is APPROVED.
            if (state.getCurrentStep() != OnboardingStep.KYC_INITIATED
                    && state.getCurrentStep() != OnboardingStep.KYC_REJECTED) {
                Span dupSpan = tracer.currentSpan();
                if (dupSpan != null) {
                    dupSpan.tag("nexus.idempotency.duplicate", "true");
                    dupSpan.tag("nexus.saga.id", state.getSagaId().toString());
                }
                log.debug("KYC approved duplicate: sagaId={} alreadyAt={}",
                        state.getSagaId(), state.getCurrentStep());
                return;
            }
            tagSagaSpan(state.getSagaId(), OnboardingStep.ACCOUNTS_CREATING);
            state.setCurrentStep(OnboardingStep.ACCOUNTS_CREATING);
            sagaRepository.save(state);

            OutboxEntry createAccountsEntry = OutboxEntry.forSagaCommand(
                    "saga.commands", state.getSagaId().toString(),
                    Map.of("commandType", "CreateAccountsCommand",
                            "targetService", "nexus-account-service",
                            "sagaId", state.getSagaId().toString(),
                            "payload", Map.of("userId", userId.toString(),
                                    "accountTypes", List.of("CHECKING", "SAVINGS"),
                                    "currency", "MXN")),
                    objectMapper);
            createAccountsEntry.attachTraceContext(tracer);
            outboxRepository.save(createAccountsEntry);

            log.info("KYC approved, creating accounts: sagaId={}", state.getSagaId());
        });
    }

    @Transactional
    public void handleKycRejected(JsonNode event) {
        UUID userId = UUID.fromString(event.path("userId").asText());
        sagaRepository.findByUserIdAndCompletedAtIsNull(userId).ifPresent(state -> {
            if (state.getCurrentStep() != OnboardingStep.KYC_INITIATED) {
                Span dupSpan = tracer.currentSpan();
                if (dupSpan != null) {
                    dupSpan.tag("nexus.idempotency.duplicate", "true");
                    dupSpan.tag("nexus.saga.id", state.getSagaId().toString());
                }
                log.debug("KYC rejected duplicate: sagaId={} alreadyAt={}",
                        state.getSagaId(), state.getCurrentStep());
                return;
            }
            boolean canRetry = event.path("canRetry").asBoolean(true);

            tagSagaSpan(state.getSagaId(), OnboardingStep.KYC_REJECTED);
            state.setCurrentStep(OnboardingStep.KYC_REJECTED);
            state.setFailureReason("KYC rejected");

            var ctx = SagaFailureContext.builder()
                    .failureType(SagaFailureContext.FailureType.KYC_REJECTED)
                    .userId(userId.toString())
                    .canRetry(canRetry)
                    .language(state.getLanguage())
                    .build();

            state.setFailureExplanation(explainerService.explain(ctx));

            if (!canRetry) {
                tagSagaSpan(state.getSagaId(), OnboardingStep.REGISTRATION_CANCELLED);
                state.setCurrentStep(OnboardingStep.REGISTRATION_CANCELLED);
                state.setCompletedAt(Instant.now());
            }
            // canRetry=true: keep completedAt=null so next approval finds this saga

            sagaRepository.save(state);
            log.info("OnboardingFlowSaga KYC rejected: sagaId={} canRetry={}", state.getSagaId(), canRetry);
        });
    }

    @Transactional
    public void handleAccountsCreated(JsonNode reply) {
        UUID sagaId = UUID.fromString(reply.path("sagaId").asText());
        sagaRepository.findById(sagaId).ifPresent(state -> {
            JsonNode payload = reply.path("payload");
            String checkingId = payload.path("checkingAccountId").asText("");
            String savingsId  = payload.path("savingsAccountId").asText("");
            if (!checkingId.isBlank()) state.setCheckingAccountId(UUID.fromString(checkingId));
            if (!savingsId.isBlank())  state.setSavingsAccountId(UUID.fromString(savingsId));
            tagSagaSpan(sagaId, OnboardingStep.ACCOUNTS_CREATED);
            state.setCurrentStep(OnboardingStep.ACCOUNTS_CREATED);
            sagaRepository.save(state);

            OutboxEntry welcomeEntry = OutboxEntry.forSagaCommand(
                    "saga.commands", sagaId.toString(),
                    Map.of("commandType", "SendWelcomeNotificationCommand",
                            "targetService", "nexus-notification-service",
                            "sagaId", sagaId.toString(),
                            "payload", Map.of("userId", state.getUserId().toString())),
                    objectMapper);
            welcomeEntry.attachTraceContext(tracer);
            outboxRepository.save(welcomeEntry);
        });
    }

    @Transactional
    public void handleAccountCreationFailed(JsonNode reply) {
        UUID sagaId = UUID.fromString(reply.path("sagaId").asText());
        sagaRepository.findById(sagaId).ifPresent(state -> {
            tagSagaSpan(sagaId, OnboardingStep.ACCOUNT_CREATION_FAILED);
            state.setCurrentStep(OnboardingStep.ACCOUNT_CREATION_FAILED);
            state.setFailureReason("Account creation failed");
            state.setCompletedAt(Instant.now());
            sagaRepository.save(state);
        });
    }

    @Transactional
    public void handleWelcomeNotificationSent(JsonNode reply) {
        UUID sagaId = UUID.fromString(reply.path("sagaId").asText());
        sagaRepository.findById(sagaId).ifPresent(state -> {
            tagSagaSpan(sagaId, OnboardingStep.COMPLETED);
            state.setCurrentStep(OnboardingStep.COMPLETED);
            state.setCompletedAt(Instant.now());
            sagaRepository.save(state);
            sagaCompletedCounter.increment();
            log.info("OnboardingFlowSaga COMPLETED: sagaId={} userId={}",
                    sagaId, state.getUserId());
        });
    }
}