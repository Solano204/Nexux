package com.nexus.saga.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.saga.application.ai.SagaFailureExplainerService;
import com.nexus.saga.domain.model.OutboxEntry;
import com.nexus.saga.domain.model.SagaFailureExplanation;
import com.nexus.saga.domain.model.transfer.TransferSagaState;
import com.nexus.saga.domain.model.transfer.TransferStep;
import com.nexus.saga.infrastructure.jpa.OutboxRepository;
import com.nexus.saga.infrastructure.jpa.TransferSagaRepository;
import com.nexus.saga.integration.support.AbstractIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test for TransferSaga — the platform's "piloto"
 * saga (see CHANGES-BESTPRACTICES/01_SAGA_PATTERN_CHANGES.md). Drives the
 * REAL TransferSagaProcessor state machine against REAL Postgres and REAL
 * Kafka (Testcontainers): every step transition below happens because a
 * real @KafkaListener (TransactionEventConsumer / SagaReplyConsumer) really
 * consumed a real message and really called TransferSagaProcessor, which
 * really wrote to Postgres.
 *
 * Was previously com.nexus.saga.integration.TransferSagaIntegrationTest -
 * a 4-line empty stub. Zero coverage existed for the single most critical
 * flow in the platform before this (see 04_TESTING_STRATEGY_CHANGES.md
 * Section 1).
 *
 * What's simulated vs. real:
 *  - REAL: saga-orchestrator's own Postgres state + Kafka consumption of
 *    transactions.initiated and saga.replies - the actual code under test.
 *  - SIMULATED: the other 3 services (account/fraud/ledger) - their replies
 *    are published directly to the real Kafka topic with the exact JSON
 *    shape their real code builds (cross-referenced against
 *    FraudAnalysisService.publishSagaReply() in fraud-service, Section 2).
 *    Running all 4 services for this would be full platform E2E, explicitly
 *    out of scope per your own "sin levantar los 14 servicios restantes."
 *  - NOT verified via raw Kafka consumption: the saga-orchestrator's OWN
 *    outbound commands (ReserveBalanceCommand etc.) go through the outbox
 *    pattern (see TransferSagaProcessor.transitionAndPublish -
 *    outboxRepository.save(cmdEntry), not a direct KafkaTemplate.send).
 *    Debezium/Kafka Connect relays outbox rows to Kafka in prod - standing
 *    that up too would turn this into an infrastructure test of Debezium,
 *    not of the saga's own logic. Asserting against the outbox table
 *    directly (real Postgres, real serialized command JSON) verifies
 *    exactly what this service is responsible for; the outbox→Kafka relay
 *    is Debezium's contract to keep, tested separately if you want that.
 *  - MOCKED (not Testcontainers, not real): SagaFailureExplainerService -
 *    it calls real OpenAI. Even an "integration" test shouldn't make real
 *    calls to a paid third-party API - that's not what Testcontainers is
 *    for, and it would make this test flaky/costly/network-dependent for
 *    no verification value (the AI text content isn't what this test cares
 *    about - the compensation mechanics are).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Tag("integration")
class TransferSagaIntegrationTest extends AbstractIntegrationTest {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired TransferSagaRepository sagaRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean SagaFailureExplainerService explainerService;

    // saga-orchestrator's @KafkaListener consumers (TransactionEventConsumer,
    // SagaReplyConsumer, IdentityEventConsumer) subscribe to all 4 topics
    // below as soon as the context starts. Relying on auto-create-on-first-
    // publish was observed to time out ("Topic transactions.initiated not
    // present in metadata after 60000 ms") once this many consumer groups
    // race to subscribe at once - create them explicitly up front instead.
    @BeforeAll
    static void createTopics() {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic("transactions.initiated", 1, (short) 1),
                    new NewTopic("saga.replies", 1, (short) 1),
                    new NewTopic("users.registered", 1, (short) 1),
                    new NewTopic("identity.verified", 1, (short) 1),
                    new NewTopic("identity.rejected", 1, (short) 1)
            )).all().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to pre-create Kafka topics", e);
        }
    }

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        sagaRepository.deleteAll();
        when(explainerService.explain(any())).thenReturn(
                SagaFailureExplanation.fallback("FRAUD_REJECTED", true, true, "es"));
    }

    @Test
    @DisplayName("happy path: TransactionInitiated through 5 real reply round-trips reaches COMPLETED")
    void transferSaga_allStepsSucceed_reachesCompleted() {
        UUID transactionId = UUID.randomUUID();
        UUID sourceAccountId = UUID.randomUUID();
        UUID targetAccountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        publishTransactionInitiated(transactionId, sourceAccountId, targetAccountId, userId);

        UUID sagaId = awaitSagaAtStep(transactionId, TransferStep.BALANCE_RESERVING);
        assertOutboxHasCommand(sagaId, "ReserveBalanceCommand");

        publishReply("BalanceReservedReply", sagaId, transactionId,
                """
                , "reservationId": "%s", "newAvailableBalance": "8500.00" """
                        .formatted(UUID.randomUUID()));
        awaitSagaAtStep(transactionId, TransferStep.FRAUD_CHECKING);
        assertOutboxHasCommand(sagaId, "CheckFraudCommand");

        publishReply("FraudClearedReply", sagaId, transactionId,
                """
                , "sourceService": "nexus-fraud-service", "riskScore": "12.50" """);
        awaitSagaAtStep(transactionId, TransferStep.LEDGER_POSTING);
        assertOutboxHasCommand(sagaId, "PostLedgerCommand");

        publishReply("LedgerPostedReply", sagaId, transactionId,
                """
                , "postingId": "%s", "debitEntryId": "%s", "creditEntryId": "%s" """
                        .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        awaitSagaAtStep(transactionId, TransferStep.BALANCE_FINALIZING);
        assertOutboxHasCommand(sagaId, "FinalizeTransferCommand");

        publishReply("BalanceFinalizedReply", sagaId, transactionId, "");
        awaitSagaAtStep(transactionId, TransferStep.NOTIFICATION_SENDING);
        assertOutboxHasCommand(sagaId, "SendTransactionNotificationCommand");

        publishReply("NotificationSentReply", sagaId, transactionId,
                """
                , "originalCommand": "SendTransactionNotificationCommand" """);
        awaitSagaAtStep(transactionId, TransferStep.COMPLETED);
    }

    @Test
    @DisplayName("fault injection: FraudRejectedReply mid-saga triggers compensation, releases the reservation, ends COMPENSATION_COMPLETED")
    void transferSaga_fraudRejectsAfterBalanceReserved_compensates() {
        UUID transactionId = UUID.randomUUID();
        UUID sourceAccountId = UUID.randomUUID();
        UUID targetAccountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        publishTransactionInitiated(transactionId, sourceAccountId, targetAccountId, userId);
        UUID sagaId = awaitSagaAtStep(transactionId, TransferStep.BALANCE_RESERVING);

        publishReply("BalanceReservedReply", sagaId, transactionId,
                """
                , "reservationId": "%s", "newAvailableBalance": "8500.00" """
                        .formatted(UUID.randomUUID()));
        awaitSagaAtStep(transactionId, TransferStep.FRAUD_CHECKING);

        // ── Injected fault: fraud-service rejects the transaction ──
        publishReply("FraudRejectedReply", sagaId, transactionId,
                """
                , "sourceService": "nexus-fraud-service", "riskScore": "92.00" """);

        // startCompensation() transitions FRAUD_CHECKING -> FRAUD_REJECTED ->
        // RELEASING_BALANCE in the same @Transactional call, so by the time
        // the state is queryable it's already at RELEASING_BALANCE, not
        // sitting at FRAUD_REJECTED - assert the compensating command instead.
        UUID reloadedSagaId = awaitSagaAtStep(transactionId, TransferStep.RELEASING_BALANCE);
        assertThat(reloadedSagaId).isEqualTo(sagaId);
        assertOutboxHasCommand(sagaId, "ReleaseBalanceCommand");

        TransferSagaState afterCompensationStart = sagaRepository.findByTransactionId(transactionId).orElseThrow();
        assertThat(afterCompensationStart.getFraudDecision()).isEqualTo("REJECTED");
        assertThat(afterCompensationStart.getFailureType()).isEqualTo("FRAUD_REJECTED");

        // account-service confirms the reservation was released
        publishReply("BalanceReleasedReply", sagaId, transactionId, "");

        awaitSagaAtStep(transactionId, TransferStep.COMPENSATION_COMPLETED);
        TransferSagaState finalState = sagaRepository.findByTransactionId(transactionId).orElseThrow();
        assertThat(finalState.getCompletedAt()).isNotNull();
        assertOutboxHasCommand(sagaId, "SendTransactionFailureNotificationCommand");

        // sagaCompensatedCounter path recorded a domain event too
        boolean sagaFailedEventWritten = outboxRepository.findByProcessedAtIsNullOrderByCreatedAtAsc()
                .stream()
                .anyMatch(e -> "transactions.saga.failed".equals(e.getTopic())
                        && sagaId.toString().equals(e.getAggregateId().toString()));
        assertThat(sagaFailedEventWritten).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void publishTransactionInitiated(UUID transactionId, UUID sourceAccountId,
                                             UUID targetAccountId, UUID userId) {
        String event = """
                {
                  "transactionId": "%s",
                  "sourceAccountId": "%s",
                  "targetAccountId": "%s",
                  "userId": "%s",
                  "amount": "1500.00",
                  "currency": "MXN",
                  "transactionType": "INTERNAL_TRANSFER",
                  "description": "saga integration test transfer",
                  "language": "es"
                }
                """.formatted(transactionId, sourceAccountId, targetAccountId, userId);
        kafkaTemplate.send("transactions.initiated", transactionId.toString(), event);
    }

    private void publishReply(String replyType, UUID sagaId, UUID transactionId,
                              String extraFieldsJson) {
        String reply = """
                {
                  "replyType": "%s",
                  "sagaId": "%s",
                  "transactionId": "%s",
                  "traceId": "trace-saga-it"%s
                }
                """.formatted(replyType, sagaId, transactionId, extraFieldsJson);
        kafkaTemplate.send("saga.replies", sagaId.toString(), reply);
    }

    /** Polls until the saga (found by transactionId) reaches the expected step, returns its sagaId. */
    private UUID awaitSagaAtStep(UUID transactionId, TransferStep expected) {
        var ref = new Object() { TransferSagaState state; };
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    TransferSagaState state = sagaRepository.findByTransactionId(transactionId)
                            .orElseThrow();
                    assertThat(state.getCurrentStep()).isEqualTo(expected);
                    ref.state = state;
                });
        return ref.state.getSagaId();
    }

    private void assertOutboxHasCommand(UUID sagaId, String expectedCommandType) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    boolean found = outboxRepository.findByProcessedAtIsNullOrderByCreatedAtAsc()
                            .stream()
                            .anyMatch(e -> sagaId.toString().equals(e.getAggregateId().toString())
                                    && expectedCommandType.equals(
                                            e.getPayload().path("commandType").asText()));
                    assertThat(found)
                            .as("outbox entry with commandType=%s for sagaId=%s", expectedCommandType, sagaId)
                            .isTrue();
                });
    }
}
