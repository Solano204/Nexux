package com.nexus.transaction.integration;

import com.nexus.transaction.application.command.TransactionCommandService;
import com.nexus.transaction.domain.model.Transaction;
import com.nexus.transaction.domain.model.enums.TransactionStatus;
import com.nexus.transaction.domain.model.enums.TransactionType;
import com.nexus.transaction.infrastructure.persistence.TransactionRepository;
import com.nexus.transaction.integration.support.AbstractIntegrationTest;
import com.nexus.transaction.web.dto.request.InitiateTransactionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for nexus-transaction-service against REAL Postgres and
 * REAL Kafka (Testcontainers, see AbstractIntegrationTest). This is the
 * layer that catches what mocks can't: actual Hibernate/Postgres schema
 * mapping (column types, constraints, the fraud_reasons _text[] array
 * mapping), and actual Kafka message deserialization/routing through
 * SagaReplyConsumer's real @KafkaListener - not a hand-invoked method call
 * like the component test in fraud-service (Section 2).
 *
 * Was previously com.nexus.transaction.integration.TransactionFlowIntegrationTest
 * - existed as a 181-line file, 100% commented out, never ran. This replaces it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Tag("integration")
class TransactionFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired TransactionCommandService commandService;
    @Autowired TransactionRepository transactionRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;

    // Explicit cleanup, NOT @Transactional rollback: the saga.replies message
    // published below is consumed by SagaReplyConsumer on Spring Kafka's own
    // listener thread, which commits its own transaction independently of
    // whatever transaction this test method might have open - a rollback on
    // the test's transaction would never touch what the listener thread
    // already committed. @Transactional rollback DOES work for the second
    // test below (idempotencyCheck_duplicateKey_returnsExistingTransaction),
    // which never leaves the calling thread - see that test's comment.
    @BeforeEach
    void cleanUp() {
        transactionRepository.deleteAll();
    }

    @Test
    @DisplayName("real Postgres + real Kafka: FraudClearedReply on saga.replies transitions transaction to FRAUD_CLEARED")
    void fraudClearedReply_realKafkaConsumption_updatesRealPostgresRow() {
        UUID sourceAccountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var request = new InitiateTransactionRequest(
                "idem-" + UUID.randomUUID(),
                sourceAccountId, UUID.randomUUID(), null, null,
                new BigDecimal("1500.00"), "MXN",
                TransactionType.INTERNAL_TRANSFER, null,
                "integration test transfer", null, null, null);

        var response = commandService.initiateTransaction(
                request, userId, "192.168.1.100", "device-001", "trace-it-001");

        // TransactionResponse only exposes transactionId as a String and has
        // no sagaId field at all - reload the entity to get both as their
        // real types instead of guessing at DTO shape.
        UUID transactionId = UUID.fromString(response.transactionId());
        UUID sagaId = transactionRepository.findById(transactionId)
                .orElseThrow().getSagaId();

        // Real saga order is balance-first: INITIATED -> BALANCE_RESERVING ->
        // BALANCE_RESERVED -> FRAUD_CLEARED (see Transaction.canTransitionTo's
        // comment) - the orchestrator always reserves balance before fraud
        // clears. Drive that step for real instead of jumping straight to
        // FRAUD_CLEARED, which the state machine correctly rejects.
        commandService.processBalanceResult(transactionId, sagaId, true, null, "trace-it-001");

        // Same JSON shape FraudAnalysisService.publishSagaReply() actually
        // builds (see nexus-fraud-service - Section 2's component test
        // verifies THAT side of this exact contract; this test verifies the
        // consuming side, over a real broker instead of a mock).
        String replyJson = """
                {
                  "replyType": "FraudClearedReply",
                  "sagaId": "%s",
                  "transactionId": "%s",
                  "sourceService": "nexus-fraud-service",
                  "decision": "APPROVE",
                  "riskScore": "12.50",
                  "fraudScore": "12.50",
                  "reasons": [],
                  "traceId": "trace-it-001"
                }
                """.formatted(sagaId, transactionId);

        kafkaTemplate.send("saga.replies", sagaId.toString(), replyJson);

        // Consumption is async (real Kafka listener thread) - poll instead
        // of asserting immediately. 10s was too tight: the saga.replies
        // @KafkaListener has to join its consumer group and get a partition
        // assignment against a freshly-started Testcontainers broker before
        // it can consume anything at all, which alone can take several
        // seconds under load.
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Transaction reloaded = transactionRepository.findById(transactionId)
                            .orElseThrow();
                    assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.FRAUD_CLEARED);
                    assertThat(reloaded.getFraudScore()).isEqualByComparingTo("12.50");
                    assertThat(reloaded.getFraudDecision()).isEqualTo("CLEARED");
                    assertThat(reloaded.getSagaStep()).isEqualTo("FRAUD_CLEARED");
                });
    }

    // @Transactional rollback works here because everything - the idempotency
    // check AND the write it triggers - runs synchronously on this test
    // method's own thread inside one Spring-managed transaction. No listener
    // thread involved, so no separate committed transaction can outlive the
    // rollback. This is the pattern for any integration test that stays
    // entirely within TransactionCommandService's own synchronous API.
    @Test
    @Transactional
    @DisplayName("real Postgres: duplicate idempotencyKey for same user returns the existing transaction, no new row")
    void idempotencyCheck_duplicateKey_returnsExistingTransaction() {
        UUID userId = UUID.randomUUID();
        String idempotencyKey = "idem-" + UUID.randomUUID();
        var request = new InitiateTransactionRequest(
                idempotencyKey, UUID.randomUUID(), UUID.randomUUID(), null, null,
                new BigDecimal("200.00"), "MXN",
                TransactionType.INTERNAL_TRANSFER, null,
                "first attempt", null, null, null);

        var first = commandService.initiateTransaction(
                request, userId, "10.0.0.1", "device-002", "trace-it-002");
        var second = commandService.initiateTransaction(
                request, userId, "10.0.0.1", "device-002", "trace-it-002");

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(transactionRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
                .hasValueSatisfying(txn ->
                        assertThat(txn.getTransactionId().toString()).isEqualTo(first.transactionId()));
    }
}
