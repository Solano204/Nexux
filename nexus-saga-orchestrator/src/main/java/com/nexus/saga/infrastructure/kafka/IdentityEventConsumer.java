package com.nexus.saga.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.onboarding.OnboardingFlowSagaProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityEventConsumer {

    private final OnboardingFlowSagaProcessor onboardingProcessor;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "users.registered",
            groupId = "saga-orchestrator-identity",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeUserRegistered(String message, Acknowledgment ack) {
        try {
            onboardingProcessor.handleUserRegistered(objectMapper.readTree(message));
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to start OnboardingFlowSaga: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(
            topics = {"identity.verified", "identity.rejected"},
            groupId = "saga-orchestrator-kyc",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeKycResult(String message, Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String eventType = event.path("eventType").asText("");
            if (eventType.contains("verified")) {
                onboardingProcessor.handleKycApproved(event);
            } else {
                onboardingProcessor.handleKycRejected(event);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process KYC result: {}", e.getMessage(), e);
        }
    }
}