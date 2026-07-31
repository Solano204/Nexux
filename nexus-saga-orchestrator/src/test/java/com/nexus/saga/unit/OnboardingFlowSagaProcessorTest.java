package com.nexus.saga.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.ai.SagaFailureExplainerService;
import com.nexus.saga.application.onboarding.OnboardingFlowSagaProcessor;
import com.nexus.saga.domain.model.SagaFailureExplanation;
import com.nexus.saga.domain.model.onboarding.OnboardingFlowSagaState;
import com.nexus.saga.domain.model.onboarding.OnboardingStep;
import com.nexus.saga.infrastructure.jpa.OnboardingSagaRepository;
import com.nexus.saga.infrastructure.jpa.OutboxRepository;
import com.nexus.saga.infrastructure.jpa.SagaStepHistoryRepository;
import com.nexus.saga.infrastructure.jpa.SagaTimeoutRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingFlowSagaProcessorTest {

    @Mock private OnboardingSagaRepository sagaRepository;
    @Mock private SagaStepHistoryRepository historyRepository;
    @Mock private SagaTimeoutRepository timeoutRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private SagaFailureExplainerService explainerService;
    @Mock private Tracer tracer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OnboardingFlowSagaProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OnboardingFlowSagaProcessor(sagaRepository, historyRepository, timeoutRepository,
                outboxRepository, explainerService, objectMapper, tracer, new SimpleMeterRegistry());
        lenient().when(sagaRepository.save(any(OnboardingFlowSagaState.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private JsonNode json(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private OnboardingFlowSagaState sagaAt(OnboardingStep step, UUID userId, UUID sagaId) {
        return OnboardingFlowSagaState.builder()
                .sagaId(sagaId).userId(userId).currentStep(step)
                .language("es").startedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    @Nested
    class HandleUserRegistered {

        @Test
        void startsNewSagaAtKycInitiated() throws Exception {
            UUID userId = UUID.randomUUID();
            when(sagaRepository.findByUserIdAndCompletedAtIsNull(userId)).thenReturn(Optional.empty());

            processor.handleUserRegistered(json(
                    "{\"userId\":\"" + userId + "\",\"email\":\"a@b.com\",\"language\":\"es\"}"));

            var captor = org.mockito.ArgumentCaptor.forClass(OnboardingFlowSagaState.class);
            verify(sagaRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrentStep()).isEqualTo(OnboardingStep.KYC_INITIATED);
            assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        }

        @Test
        void isIdempotentWhenActiveSagaAlreadyExists() throws Exception {
            UUID userId = UUID.randomUUID();
            when(sagaRepository.findByUserIdAndCompletedAtIsNull(userId))
                    .thenReturn(Optional.of(sagaAt(OnboardingStep.KYC_INITIATED, userId, UUID.randomUUID())));

            processor.handleUserRegistered(json("{\"userId\":\"" + userId + "\"}"));

            verify(sagaRepository, never()).save(any());
        }
    }

    @Nested
    class HandleKycApproved {

        @Test
        void advancesToAccountsCreatingAndQueuesCommand() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();
            when(sagaRepository.findByUserIdAndCompletedAtIsNull(userId))
                    .thenReturn(Optional.of(sagaAt(OnboardingStep.KYC_INITIATED, userId, sagaId)));

            processor.handleKycApproved(json("{\"userId\":\"" + userId + "\"}"));

            var captor = org.mockito.ArgumentCaptor.forClass(OnboardingFlowSagaState.class);
            verify(sagaRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrentStep()).isEqualTo(OnboardingStep.ACCOUNTS_CREATING);
            verify(outboxRepository).save(any());
        }

        @Test
        void recoversFromKycRejectedWhenLateApprovalArrives() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();
            when(sagaRepository.findByUserIdAndCompletedAtIsNull(userId))
                    .thenReturn(Optional.of(sagaAt(OnboardingStep.KYC_REJECTED, userId, sagaId)));

            processor.handleKycApproved(json("{\"userId\":\"" + userId + "\"}"));

            verify(sagaRepository).save(argThat(s -> s.getCurrentStep() == OnboardingStep.ACCOUNTS_CREATING));
        }

        @Test
        void ignoresDuplicateApprovalPastAccountsCreating() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();
            when(sagaRepository.findByUserIdAndCompletedAtIsNull(userId))
                    .thenReturn(Optional.of(sagaAt(OnboardingStep.ACCOUNTS_CREATED, userId, sagaId)));

            processor.handleKycApproved(json("{\"userId\":\"" + userId + "\"}"));

            verify(sagaRepository, never()).save(any());
            verifyNoInteractions(outboxRepository);
        }
    }

    @Nested
    class HandleKycRejected {

        @Test
        void permanentRejectionCancelsRegistrationImmediately() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();
            when(sagaRepository.findByUserIdAndCompletedAtIsNull(userId))
                    .thenReturn(Optional.of(sagaAt(OnboardingStep.KYC_INITIATED, userId, sagaId)));
            when(explainerService.explain(any())).thenReturn(
                    SagaFailureExplanation.fallback("KYC_REJECTED", true, false, "es"));

            processor.handleKycRejected(json("{\"userId\":\"" + userId + "\",\"canRetry\":false}"));

            var captor = org.mockito.ArgumentCaptor.forClass(OnboardingFlowSagaState.class);
            verify(sagaRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrentStep()).isEqualTo(OnboardingStep.REGISTRATION_CANCELLED);
            assertThat(captor.getValue().getCompletedAt()).isNotNull();
        }

        @Test
        void retryableRejectionKeepsSagaOpen() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();
            when(sagaRepository.findByUserIdAndCompletedAtIsNull(userId))
                    .thenReturn(Optional.of(sagaAt(OnboardingStep.KYC_INITIATED, userId, sagaId)));
            when(explainerService.explain(any())).thenReturn(
                    SagaFailureExplanation.fallback("KYC_REJECTED", true, true, "es"));

            processor.handleKycRejected(json("{\"userId\":\"" + userId + "\",\"canRetry\":true}"));

            var captor = org.mockito.ArgumentCaptor.forClass(OnboardingFlowSagaState.class);
            verify(sagaRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrentStep()).isEqualTo(OnboardingStep.KYC_REJECTED);
            assertThat(captor.getValue().getCompletedAt()).isNull();
        }

        @Test
        void ignoresRejectionForSagaNotAtKycInitiated() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();
            when(sagaRepository.findByUserIdAndCompletedAtIsNull(userId))
                    .thenReturn(Optional.of(sagaAt(OnboardingStep.ACCOUNTS_CREATING, userId, sagaId)));

            processor.handleKycRejected(json("{\"userId\":\"" + userId + "\",\"canRetry\":true}"));

            verify(sagaRepository, never()).save(any());
            verifyNoInteractions(explainerService);
        }
    }

    @Nested
    class HandleAccountsCreated {

        @Test
        void storesAccountIdsAndQueuesWelcomeNotification() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();
            UUID checkingId = UUID.randomUUID();
            UUID savingsId = UUID.randomUUID();
            when(sagaRepository.findById(sagaId))
                    .thenReturn(Optional.of(sagaAt(OnboardingStep.ACCOUNTS_CREATING, userId, sagaId)));

            processor.handleAccountsCreated(json(String.format(
                    "{\"sagaId\":\"%s\",\"payload\":{\"checkingAccountId\":\"%s\",\"savingsAccountId\":\"%s\"}}",
                    sagaId, checkingId, savingsId)));

            var captor = org.mockito.ArgumentCaptor.forClass(OnboardingFlowSagaState.class);
            verify(sagaRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrentStep()).isEqualTo(OnboardingStep.ACCOUNTS_CREATED);
            assertThat(captor.getValue().getCheckingAccountId()).isEqualTo(checkingId);
            assertThat(captor.getValue().getSavingsAccountId()).isEqualTo(savingsId);
            verify(outboxRepository).save(any());
        }
    }

    @Nested
    class HandleAccountCreationFailed {

        @Test
        void marksSagaFailedAndTerminal() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();
            when(sagaRepository.findById(sagaId))
                    .thenReturn(Optional.of(sagaAt(OnboardingStep.ACCOUNTS_CREATING, userId, sagaId)));

            processor.handleAccountCreationFailed(json("{\"sagaId\":\"" + sagaId + "\"}"));

            var captor = org.mockito.ArgumentCaptor.forClass(OnboardingFlowSagaState.class);
            verify(sagaRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrentStep()).isEqualTo(OnboardingStep.ACCOUNT_CREATION_FAILED);
            assertThat(captor.getValue().getCompletedAt()).isNotNull();
        }
    }

    @Nested
    class HandleWelcomeNotificationSent {

        @Test
        void completesSagaAndIncrementsCounter() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID sagaId = UUID.randomUUID();
            when(sagaRepository.findById(sagaId))
                    .thenReturn(Optional.of(sagaAt(OnboardingStep.WELCOME_NOTIFICATION_SENT, userId, sagaId)));

            processor.handleWelcomeNotificationSent(json("{\"sagaId\":\"" + sagaId + "\"}"));

            var captor = org.mockito.ArgumentCaptor.forClass(OnboardingFlowSagaState.class);
            verify(sagaRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrentStep()).isEqualTo(OnboardingStep.COMPLETED);
            assertThat(captor.getValue().getCompletedAt()).isNotNull();
        }
    }
}
