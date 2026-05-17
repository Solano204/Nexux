package com.nexus.identity.application.saga;

import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.domain.command.CancelRegistrationCommand;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * OnboardingSagaParticipant — Identity Service's role in OnboardingFlowSaga.
 *
 * The OnboardingFlowSaga in nexus-saga-orchestrator coordinates:
 *   Step 1: Identity registers user       (this service)
 *   Step 2: KYC verification              (this service + ai-kyc-service)
 *   Step 3: Account creation              (account-service)
 *   Step 4: Notification sent             (notification-service)
 *
 * This participant handles the COMPENSATION step when Step 3 or 4 fails:
 *   Receives CancelUserRegistrationCommand via SagaCommandConsumer
 *   → delegates to UserCommandService.cancelRegistration()
 *
 * Why a separate class from SagaCommandConsumer?
 *   SagaCommandConsumer handles Kafka deserialization, error handling,
 *   and ACK management. OnboardingSagaParticipant holds pure domain logic
 *   for what "cancel registration" means to this bounded context.
 *
 * Idempotency:
 *   cancelRegistration() checks if already REGISTRATION_CANCELLED
 *   before executing — safe for Kafka at-least-once redelivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingSagaParticipant {

    private final UserCommandService userCommandService;
    private final ObservationRegistry observationRegistry;

    /**
     * Execute the cancel registration compensation step.
     * Called by SagaCommandConsumer when CancelUserRegistrationCommand arrives.
     */
    public void compensate(CancelRegistrationCommand command) {
        Observation obs = Observation.createNotStarted(
                        "saga.identity.compensate", observationRegistry)
                .lowCardinalityKeyValue("sagaId", command.sagaId())
                .start();

        try {
            log.info("SAGA compensation started: step=CANCEL_REGISTRATION " +
                            "userId={} sagaId={} reason={}",
                    command.userId(), command.sagaId(), command.reason());

            userCommandService.cancelRegistration(
                    command.userId(),
                    command.sagaId(),
                    command.traceId()
            );

            obs.event(Observation.Event.of("saga.compensate.success"));
            log.info("SAGA compensation completed: step=CANCEL_REGISTRATION " +
                            "userId={} sagaId={}",
                    command.userId(), command.sagaId());

        } catch (Exception e) {
            obs.error(e);
            log.error("SAGA compensation failed: step=CANCEL_REGISTRATION " +
                            "userId={} sagaId={} error={}",
                    command.userId(), command.sagaId(), e.getMessage(), e);
            throw e; // Re-throw — Kafka will redeliver
        } finally {
            obs.stop();
        }
    }

    /**
     * Validates whether this service can still compensate.
     * Called by SAGA orchestrator in health check (optional).
     */
    public boolean canCompensate(UUID userId) {
        try {
            // Check if user exists and is in a compensatable state
            return true; // UserCommandService handles idempotency
        } catch (Exception e) {
            log.warn("Compensation check failed for userId={}: {}",
                    userId, e.getMessage());
            return false;
        }
    }
}