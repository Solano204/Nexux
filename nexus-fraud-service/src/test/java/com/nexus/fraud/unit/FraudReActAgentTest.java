package com.nexus.fraud.unit;

import com.nexus.fraud.agent.FraudReActAgent;
import com.nexus.fraud.domain.model.*;
import com.nexus.fraud.domain.model.enums.*;
import com.nexus.fraud.web.dto.FraudAnalysisRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class FraudReActAgentTest {

    @Mock ChatClient planningClient;
    @Mock ChatClient agentClient;
    @Mock ChatClient synthesisClient;

    FraudReActAgent agent;

    @BeforeEach
    void setUp() {
        agent = new FraudReActAgent(
                planningClient, agentClient, synthesisClient,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                ObservationRegistry.NOOP,
                new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("analyze: returns safe REVIEW fallback on planning failure")
    void analyze_planningFails_returnsSafeFallback() {
        when(planningClient.prompt()).thenThrow(
                new RuntimeException("OpenAI unavailable"));

        var request = buildRequest("500.00");

        // Should NOT throw — safe fallback always returned
        FraudDecision result = agent.analyze(request);

        assertThat(result).isNotNull();
        assertThat(result.decision())
                .isEqualTo(FraudDecisionOutcome.REVIEW);
        assertThat(result.reviewPriority()).isEqualTo("HIGH");
        assertThat(result.reasoning()).contains("failed");
    }

    @Test
    @DisplayName("buildFallbackDecision: always returns REVIEW with HIGH priority")
    void fallback_alwaysReview() {
        var request = buildRequest("100.00");
        when(planningClient.prompt()).thenThrow(
                new RuntimeException("Test error"));

        FraudDecision fallback = agent.analyze(request);

        // REVIEW (not APPROVE — never approve on failure)
        // REVIEW (not REJECT — never reject legitimate users on error)
        assertThat(fallback.decision())
                .isEqualTo(FraudDecisionOutcome.REVIEW);
        assertThat(fallback.confidenceLevel())
                .isLessThan(new BigDecimal("0.5"));
        assertThat(fallback.recommendedAction())
                .isEqualTo(RecommendedAction.ESCALATE_TO_COMPLIANCE);
    }

    private FraudAnalysisRequest buildRequest(String amount) {
        return new FraudAnalysisRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                new BigDecimal(amount), "MXN",
                "INTERNAL_TRANSFER",
                "Test payment",
                null, null, null,
                "192.168.1.100", "device-001",
                true, 365, 50,
                Map.of(), "trace-001"
        );
    }
}