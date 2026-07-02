package com.nexus.saga.infrastructure.jpa;

import com.nexus.saga.domain.model.onboarding.OnboardingFlowSagaState;
import com.nexus.saga.domain.model.onboarding.OnboardingStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OnboardingSagaRepository
        extends JpaRepository<OnboardingFlowSagaState, UUID> {

    Optional<OnboardingFlowSagaState> findByUserIdAndCompletedAtIsNull(UUID userId);

    List<OnboardingFlowSagaState> findByCurrentStep(OnboardingStep step);

    Optional<OnboardingFlowSagaState> findByUserId(UUID userId);
}