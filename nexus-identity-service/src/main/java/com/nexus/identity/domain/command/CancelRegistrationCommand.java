package com.nexus.identity.domain.command;

import java.util.UUID;

/**
 * CancelRegistrationCommand — SAGA compensation command.
 * Sent by OnboardingSagaOrchestrator when a downstream step fails.
 * Consumed by SagaCommandConsumer via Kafka topic saga.commands.
 */
public record CancelRegistrationCommand(
        UUID userId,
        String sagaId,
        String reason,
        String traceId
) {}