package com.nexus.account.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.account.application.command.AccountCommandService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * SAGA Command Consumer — Account Service SAGA participant.
 *
 * Subscribes to: saga.commands
 * Handles:
 * - ReserveBalanceCommand (TransferSaga step 1)
 * - ReleaseBalanceCommand (TransferSaga compensation)
 * - FinalizeTransferCommand (TransferSaga step 4)
 * - CreateAccountsCommand (OnboardingFlowSaga step 3)
 *
 * Publishes replies to: saga.replies
 *
 * Pattern: Observer Pattern — reacts to orchestrator commands
 * Pattern: Template Method — consistent idempotency + reply pattern
 *
 * Idempotency: All handlers check for existing results before executing.
 * Handles Kafka at-least-once redelivery safely.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaCommandConsumer {

    private final AccountCommandService commandService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    private static final String REPLIES_TOPIC = "saga.replies";
    private static final String TARGET_SERVICE = "nexus-account-service";

    @KafkaListener(
            topics = "saga.commands",
            groupId = "account-service-saga-commands",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeSagaCommand(String message, Acknowledgment ack) {

        Observation obs = Observation.createNotStarted(
                        "kafka.message.processed", observationRegistry)
                .lowCardinalityKeyValue("topic", "saga.commands")
                .lowCardinalityKeyValue("consumerGroup",
                        "account-service-saga-commands")
                .start();

        try {
            JsonNode command = objectMapper.readTree(message);
            String commandType = command.path("commandType").asText();
            String targetService = command.path("targetService").asText();
            String sagaId = command.path("sagaId").asText();
            String traceId = command.path("traceId").asText();

            // Only process commands for account service
            if (!TARGET_SERVICE.equals(targetService)) {
                ack.acknowledge();
                return;
            }

            log.info("SAGA command received: type={} sagaId={}",
                    commandType, sagaId);

            switch (commandType) {
                case "ReserveBalanceCommand" -> {
                    handleReserveBalance(command, sagaId, traceId);
                    obs.event(Observation.Event.of(
                            "saga.ReserveBalance.processed"));
                }
                case "ReleaseBalanceCommand" -> {
                    handleReleaseBalance(command, sagaId, traceId);
                    obs.event(Observation.Event.of(
                            "saga.ReleaseBalance.processed"));
                }
                case "FinalizeTransferCommand" -> {
                    handleFinalizeTransfer(command, sagaId, traceId);
                    obs.event(Observation.Event.of(
                            "saga.FinalizeTransfer.processed"));
                }
                case "CreateAccountsCommand" -> {
                    handleCreateAccounts(command, sagaId, traceId);
                    obs.event(Observation.Event.of(
                            "saga.CreateAccounts.processed"));
                }
                default -> log.warn(
                        "Unknown command type for account service: {}",
                        commandType);
            }

            ack.acknowledge();
            obs.event(Observation.Event.of("kafka.message.success"));

        } catch (Exception e) {
            obs.error(e);
            log.error("Failed to process SAGA command: {}",
                    e.getMessage(), e);
            // Do NOT acknowledge — let Kafka redeliver
        } finally {
            obs.stop();
        }
    }

    private void handleReserveBalance(JsonNode command, String sagaId,
                                      String traceId) throws Exception {
        JsonNode payload = command.path("payload");
        UUID accountId = UUID.fromString(
                payload.path("accountId").asText());
        UUID transactionId = UUID.fromString(
                payload.path("transactionId").asText());
        BigDecimal amount = new BigDecimal(
                payload.path("amount").asText());
        String commandId = command.path("commandId").asText();

        AccountCommandService.BalanceOperationResult result =
                commandService.reserveBalance(
                        accountId, transactionId, amount, traceId);

        // Publish reply to saga.replies
        String replyType = result.success()
                ? "BalanceReservedReply"
                : "BalanceReservationFailedReply";

        Map<String, Object> reply = Map.of(
                "replyType", replyType,
                "sagaId", sagaId,
                "commandId", commandId,
                "transactionId", transactionId.toString(),
                "sourceService", TARGET_SERVICE,
                "success", result.success(),
                "payload", result.success()
                        ? Map.of(
                        "reservedAmount",
                        result.amount().toPlainString(),
                        "newAvailableBalance",
                        result.newAvailableBalance() != null
                                ? result.newAvailableBalance().toPlainString()
                                : "0",
                        "idempotentReplay", result.idempotentReplay())
                        : Map.of("reason", result.failureReason()),
                "repliedAt", java.time.Instant.now().toString()
        );

        kafkaTemplate.send(REPLIES_TOPIC, sagaId,
                objectMapper.writeValueAsString(reply));

        log.info("ReserveBalance reply sent: sagaId={} result={}",
                sagaId, replyType);
    }

    private void handleReleaseBalance(JsonNode command, String sagaId,
                                      String traceId) throws Exception {
        JsonNode payload = command.path("payload");
        UUID accountId = UUID.fromString(
                payload.path("accountId").asText());
        UUID transactionId = UUID.fromString(
                payload.path("transactionId").asText());
        BigDecimal amount = new BigDecimal(
                payload.path("amount").asText());
        String commandId = command.path("commandId").asText();

        AccountCommandService.BalanceOperationResult result =
                commandService.releaseBalance(
                        accountId, transactionId, amount, traceId);

        Map<String, Object> reply = Map.of(
                "replyType", "BalanceReleasedReply",
                "sagaId", sagaId,
                "commandId", commandId,
                "transactionId", transactionId.toString(),
                "sourceService", TARGET_SERVICE,
                "success", result.success(),
                "repliedAt", java.time.Instant.now().toString()
        );

        kafkaTemplate.send(REPLIES_TOPIC, sagaId,
                objectMapper.writeValueAsString(reply));
    }

    private void handleFinalizeTransfer(JsonNode command, String sagaId,
                                        String traceId) throws Exception {
        JsonNode payload = command.path("payload");
        UUID sourceAccountId = UUID.fromString(
                payload.path("sourceAccountId").asText());
        UUID targetAccountId = UUID.fromString(
                payload.path("targetAccountId").asText());
        UUID transactionId = UUID.fromString(
                payload.path("transactionId").asText());
        BigDecimal amount = new BigDecimal(
                payload.path("amount").asText());
        String commandId = command.path("commandId").asText();

        commandService.finalizeTransfer(
                sourceAccountId, targetAccountId,
                transactionId, amount, traceId);

        Map<String, Object> reply = Map.of(
                "replyType", "BalanceFinalizedReply",
                "sagaId", sagaId,
                "commandId", commandId,
                "transactionId", transactionId.toString(),
                "sourceService", TARGET_SERVICE,
                "success", true,
                "repliedAt", java.time.Instant.now().toString()
        );

        kafkaTemplate.send(REPLIES_TOPIC, sagaId,
                objectMapper.writeValueAsString(reply));
    }

    private void handleCreateAccounts(JsonNode command, String sagaId,
                                      String traceId) throws Exception {
        JsonNode payload = command.path("payload");
        UUID userId = UUID.fromString(payload.path("userId").asText());
        String currency = payload.path("currency").asText("MXN");
        String commandId = command.path("commandId").asText();

        var accounts = commandService.createDefaultAccounts(
                userId, currency, traceId);

        Map<String, Object> reply = Map.of(
                "replyType", "AccountsCreatedReply",
                "sagaId", sagaId,
                "commandId", commandId,
                "sourceService", TARGET_SERVICE,
                "success", true,
                "payload", Map.of(
                        "userId", userId.toString(),
                        "accountsCreated", accounts.size()),
                "repliedAt", java.time.Instant.now().toString()
        );

        kafkaTemplate.send(REPLIES_TOPIC, sagaId,
                objectMapper.writeValueAsString(reply));

        log.info("Accounts created via SAGA: userId={} sagaId={} " +
                "count={}", userId, sagaId, accounts.size());
    }
}