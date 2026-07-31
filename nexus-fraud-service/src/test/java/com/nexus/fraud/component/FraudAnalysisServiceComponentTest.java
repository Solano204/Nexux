package com.nexus.fraud.component;

import com.nexus.fraud.application.FraudAnalysisService;
import com.nexus.fraud.domain.model.FraudDecision;
import com.nexus.fraud.domain.model.FraudDecisionEntity;
import com.nexus.fraud.domain.model.enums.FraudDecisionOutcome;
import com.nexus.fraud.infrastructure.ai.FraudLlmGateway;
import com.nexus.fraud.infrastructure.persistence.FraudDecisionRepository;
import com.nexus.fraud.infrastructure.persistence.OutboxRepository;
import com.nexus.fraud.infrastructure.redis.FraudRedisRepository;
import com.nexus.fraud.web.dto.FraudAnalysisRequest;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Component test for nexus-fraud-service.
 *
 * Boots the REAL Spring context (real FraudAnalysisService, real
 * FraudReActAgent, real @Transactional AOP, real bean wiring) so any
 * misconfiguration in this service's own wiring is caught - but everything
 * that crosses the service boundary is a @MockBean:
 *   - FraudDecisionRepository / OutboxRepository → this service's own
 *     Postgres, mocked because a component test's job is "does MY logic
 *     behave correctly", not "does Postgres behave correctly" (that's
 *     Testcontainers' job, Section 4).
 *   - FraudRedisRepository → same reasoning, this service's own Redis.
 *   - KafkaTemplate<String,String> → the saga.replies reply is the
 *     contract fraud-service has with saga-orchestrator; mocking it here
 *     lets us assert on exactly what gets published without needing a
 *     broker. Its return value is never used by publishSagaReply() (fire
 *     and forget), so it's left unstubbed - Mockito's default null return
 *     is fine.
 *   - FraudLlmGateway → the only genuinely external dependency (OpenAI).
 *   - SqsClient → AWS, external.
 * TransactionServiceClient is NOT mocked - it's only reached by
 * FraudReActAgent's tool-execution phase, which none of these scenarios
 * get to (hard-reject short-circuits before it, and the AI-fallback
 * scenario fails at the planning phase, before any tool call).
 *
 * Entry point is FraudAnalysisService.analyze(...) directly, not MockMvc -
 * fraud-service has no public HTTP entry point for this flow, its real
 * trigger is a Kafka message (FraudCommandConsumer). Testing at
 * FraudCommandConsumer.consumeFraudCommand(...) would mean building a raw
 * ConsumerRecord and a Tracer/Propagator/Acknowledgment just to exercise
 * parsing glue that has nothing to do with fraud logic - analyze() is this
 * service's actual "component API" boundary. See the class-level note in
 * 04_TESTING_STRATEGY_CHANGES.md Section 2 for the account-service /
 * api-gateway variant that DOES use WebTestClient, for services whose real
 * trigger is HTTP.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // FraudDecisionRepository/OutboxRepository are @MockitoBean
                // (see class Javadoc) so this component test never queries
                // application data for real - but analyze() is @Transactional,
                // and JpaTransactionManager binds a real JDBC Connection at
                // transaction start regardless of mocked repositories, so a
                // real (if trivial) database is unavoidable here. H2 in place
                // of the placeholder Postgres URL from application-test.yml
                // keeps this test infra-free (no Testcontainers, that's
                // Section 4's job) while still exercising the real
                // @Transactional AOP the class Javadoc calls out.
                "spring.datasource.url=jdbc:h2:mem:fraud_component_test;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                // No broker in this test (see class Javadoc) - the real
                // @KafkaListener in FraudCommandConsumer must never call
                // start(), or it hangs retrying a connection to nothing.
                "nexus.kafka.listener.auto-startup=false"
        })
@ActiveProfiles("test")
@Tag("component")
class FraudAnalysisServiceComponentTest {

    @Autowired
    FraudAnalysisService fraudAnalysisService;

    // @MockitoBean, not the deprecated @MockBean (Spring Boot 3.5.3 / Spring
    // Framework 6.2 - @MockBean is deprecated since Boot 3.4 in favor of this).
    @MockitoBean FraudDecisionRepository decisionRepository;
    @MockitoBean OutboxRepository outboxRepository;
    @MockitoBean FraudRedisRepository redisRepository;
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean FraudLlmGateway llmGateway;
    @MockitoBean SqsClient sqsClient;
    // RagPolicyTool (invoked directly by FraudReActAgent's Act phase, not
    // through FraudLlmGateway) depends on PgVectorStore, whose real
    // autoconfiguration validates the fraud_policy_embeddings table exists
    // at bean-creation time - crosses the service boundary same as Redis/Kafka.
    @MockitoBean PgVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        when(decisionRepository.findByTransactionId(any(UUID.class)))
                .thenReturn(Optional.empty());
        when(redisRepository.isMerchantBlacklisted(anyString())).thenReturn(false);
        when(redisRepository.isAccountFlagged(anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("hard reject: blacklisted merchant short-circuits AI, persists REJECT, replies FraudRejectedReply")
    void hardReject_blacklistedMerchant_skipsAiPublishesReject() {
        String merchantId = UUID.randomUUID().toString();
        when(redisRepository.isMerchantBlacklisted(merchantId)).thenReturn(true);

        FraudAnalysisRequest request = buildRequest(null, "2500.00", merchantId);

        FraudDecision decision = fraudAnalysisService.analyze(request);

        assertThat(decision.decision()).isEqualTo(FraudDecisionOutcome.REJECT);

        // AI never called - hard rule short-circuits before agent.analyze()
        verifyNoInteractions(llmGateway);

        ArgumentCaptor<FraudDecisionEntity> entityCaptor =
                ArgumentCaptor.forClass(FraudDecisionEntity.class);
        verify(decisionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getDecisionOutcome()).isEqualTo("REJECT");

        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, String> reply = recordCaptor.getValue();
        assertThat(reply.topic()).isEqualTo("saga.replies");
        assertThat(reply.value()).contains("\"replyType\":\"FraudRejectedReply\"");
        assertThat(reply.value()).contains("\"decision\":\"REJECT\"");

        verify(redisRepository).addRecentDecision(
                eq(request.userId()), eq(request.transactionId()),
                eq("REJECT"), any());
    }

    @Test
    @DisplayName("AI planning failure: safe REVIEW fallback still gets persisted and replied")
    void aiPlanningFails_persistsSafeReviewFallback() {
        when(llmGateway.plan(anyString()))
                .thenThrow(new RuntimeException("OpenAI unavailable"));

        FraudAnalysisRequest request = buildRequest(null, "500.00", null);

        FraudDecision decision = fraudAnalysisService.analyze(request);

        assertThat(decision.decision()).isEqualTo(FraudDecisionOutcome.REVIEW);

        verify(decisionRepository).save(any(FraudDecisionEntity.class));

        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(recordCaptor.capture());
        assertThat(recordCaptor.getValue().value())
                .contains("\"replyType\":\"FraudReviewReply\"");
    }

    @Test
    @DisplayName("idempotent replay: existing decision for transactionId short-circuits, no new save/publish")
    void idempotentReplay_existingDecision_noNewSideEffects() {
        UUID txnId = UUID.randomUUID();
        FraudDecisionEntity existing = FraudDecisionEntity.builder()
                .decisionId(UUID.randomUUID())
                .transactionId(txnId)
                .decisionOutcome("APPROVE")
                .riskScore(new BigDecimal("10"))
                .confidenceLevel(new BigDecimal("0.9"))
                .recommendedAction("PROCEED_NORMALLY")
                .reviewPriority("LOW")
                .analysisTimeMs(120)
                .build();
        when(decisionRepository.findByTransactionId(txnId))
                .thenReturn(Optional.of(existing));

        FraudAnalysisRequest request = buildRequest(txnId.toString(), "300.00", null);

        FraudDecision decision = fraudAnalysisService.analyze(request);

        assertThat(decision.decision()).isEqualTo(FraudDecisionOutcome.APPROVE);
        verify(decisionRepository, never()).save(any());
        // Not verifyNoInteractions(kafkaTemplate): Spring's own
        // DeadLetterPublishingRecoverer bean touches this mock during
        // context startup (unrelated to analyze()'s idempotent-replay
        // path), so the precise business assertion is that no reply was
        // ever sent, not that the mock was never touched at all.
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verifyNoInteractions(llmGateway);
    }

    private FraudAnalysisRequest buildRequest(String transactionId, String amount, String merchantId) {
        return new FraudAnalysisRequest(
                transactionId != null ? transactionId : UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                new BigDecimal(amount), "MXN",
                "INTERNAL_TRANSFER",
                "Component test payment",
                merchantId, merchantId != null ? "Test Merchant" : null, null,
                "192.168.1.100", "device-001",
                true, 365, 50,
                Map.of(), "trace-component-test"
        );
    }
}
