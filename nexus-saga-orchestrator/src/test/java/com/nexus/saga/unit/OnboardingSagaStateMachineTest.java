package com.nexus.saga.unit;

import com.nexus.saga.domain.model.onboarding.OnboardingFlowSagaState;
import com.nexus.saga.domain.model.onboarding.OnboardingStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingSagaStateMachineTest {

    @Test
    void happyPathIsFullyReachableStepByStep() {
        assertThat(OnboardingStep.STARTED.canTransitionTo(OnboardingStep.KYC_INITIATED)).isTrue();
        assertThat(OnboardingStep.KYC_INITIATED.canTransitionTo(OnboardingStep.KYC_IN_PROGRESS)).isTrue();
        assertThat(OnboardingStep.KYC_IN_PROGRESS.canTransitionTo(OnboardingStep.KYC_APPROVED)).isTrue();
        assertThat(OnboardingStep.KYC_APPROVED.canTransitionTo(OnboardingStep.ACCOUNTS_CREATING)).isTrue();
        assertThat(OnboardingStep.ACCOUNTS_CREATING.canTransitionTo(OnboardingStep.ACCOUNTS_CREATED)).isTrue();
        assertThat(OnboardingStep.ACCOUNTS_CREATED.canTransitionTo(OnboardingStep.WELCOME_NOTIFICATION_SENT)).isTrue();
        assertThat(OnboardingStep.WELCOME_NOTIFICATION_SENT.canTransitionTo(OnboardingStep.COMPLETED)).isTrue();
    }

    @Test
    void accountsCreatedCanSkipDirectlyToCompletedWhenNotificationIsBestEffort() {
        assertThat(OnboardingStep.ACCOUNTS_CREATED.canTransitionTo(OnboardingStep.COMPLETED)).isTrue();
    }

    @Test
    void documentAwaitingPathLoopsBackIntoKycInitiated() {
        assertThat(OnboardingStep.STARTED.canTransitionTo(OnboardingStep.AWAITING_DOCUMENT)).isTrue();
        assertThat(OnboardingStep.AWAITING_DOCUMENT.canTransitionTo(OnboardingStep.KYC_INITIATED)).isTrue();
    }

    @Test
    void kycRejectionAllowsRetryOrCompensation() {
        assertThat(OnboardingStep.KYC_REJECTED.canTransitionTo(OnboardingStep.AWAITING_RETRY)).isTrue();
        assertThat(OnboardingStep.AWAITING_RETRY.canTransitionTo(OnboardingStep.KYC_INITIATED)).isTrue();
        assertThat(OnboardingStep.KYC_REJECTED.canTransitionTo(OnboardingStep.COMPENSATING_REGISTRATION)).isTrue();
    }

    @Test
    void timeoutAndAccountCreationFailureBothLeadToCompensation() {
        assertThat(OnboardingStep.KYC_TIMEOUT.canTransitionTo(OnboardingStep.COMPENSATING_REGISTRATION)).isTrue();
        assertThat(OnboardingStep.ACCOUNT_CREATION_FAILED.canTransitionTo(OnboardingStep.COMPENSATING_REGISTRATION)).isTrue();
    }

    @Test
    void compensationCanEndInThreeDifferentTerminalStates() {
        assertThat(OnboardingStep.COMPENSATING_REGISTRATION.canTransitionTo(OnboardingStep.REGISTRATION_CANCELLED)).isTrue();
        assertThat(OnboardingStep.COMPENSATING_REGISTRATION.canTransitionTo(OnboardingStep.COMPENSATION_COMPLETED)).isTrue();
        assertThat(OnboardingStep.COMPENSATING_REGISTRATION.canTransitionTo(OnboardingStep.PERMANENTLY_FAILED)).isTrue();
    }

    @Test
    void manualReviewCanApproveOrReject() {
        assertThat(OnboardingStep.AWAITING_MANUAL_REVIEW.canTransitionTo(OnboardingStep.KYC_APPROVED)).isTrue();
        assertThat(OnboardingStep.AWAITING_MANUAL_REVIEW.canTransitionTo(OnboardingStep.KYC_REJECTED)).isTrue();
    }

    @Test
    void rejectsSkippingStepsInHappyPath() {
        assertThat(OnboardingStep.STARTED.canTransitionTo(OnboardingStep.ACCOUNTS_CREATED)).isFalse();
        assertThat(OnboardingStep.KYC_INITIATED.canTransitionTo(OnboardingStep.COMPLETED)).isFalse();
    }

    @Test
    void rejectsMovingBackwardsFromCompletedStep() {
        assertThat(OnboardingStep.ACCOUNTS_CREATED.canTransitionTo(OnboardingStep.KYC_APPROVED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OnboardingStep.class, names = {
            "COMPLETED", "REGISTRATION_CANCELLED", "COMPENSATION_COMPLETED", "PERMANENTLY_FAILED"
    })
    void terminalStepsAllowNoFurtherTransitions(OnboardingStep terminal) {
        for (OnboardingStep candidate : OnboardingStep.values()) {
            assertThat(terminal.canTransitionTo(candidate)).isFalse();
        }
    }

    @Test
    void isTerminalMatchesTheDocumentedTerminalSet() {
        var expectedTerminal = EnumSet.of(
                OnboardingStep.COMPLETED, OnboardingStep.REGISTRATION_CANCELLED,
                OnboardingStep.COMPENSATION_COMPLETED, OnboardingStep.PERMANENTLY_FAILED);

        for (OnboardingStep step : OnboardingStep.values()) {
            assertThat(step.isTerminal()).isEqualTo(expectedTerminal.contains(step));
        }
    }

    @Test
    void isAfterUsesDeclarationOrderAsHappyPathOrdering() {
        assertThat(OnboardingStep.ACCOUNTS_CREATED.isAfter(OnboardingStep.KYC_APPROVED)).isTrue();
        assertThat(OnboardingStep.STARTED.isAfter(OnboardingStep.COMPLETED)).isFalse();
    }

    @Test
    void sagaStateIsTerminalDelegatesToCurrentStep() {
        OnboardingFlowSagaState state = OnboardingFlowSagaState.builder()
                .sagaId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .currentStep(OnboardingStep.COMPLETED)
                .startedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .lastUpdatedAt(Instant.now())
                .build();

        assertThat(state.isTerminal()).isTrue();
    }

    @Test
    void sagaStateIsNotTerminalMidFlow() {
        OnboardingFlowSagaState state = OnboardingFlowSagaState.builder()
                .sagaId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .currentStep(OnboardingStep.ACCOUNTS_CREATING)
                .startedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .lastUpdatedAt(Instant.now())
                .build();

        assertThat(state.isTerminal()).isFalse();
    }
}
