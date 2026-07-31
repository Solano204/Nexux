package com.nexus.fraud.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Fraud Service Contract Tests.
 *
 * Verifies the /internal/v1/fraud/** response contracts and the
 * X-Internal-Service allow-list boundary consumers depend on:
 *   nexus-transaction-service, nexus-saga-orchestrator,
 *   nexus-ai-assistant-service, nexus-audit-query-jvm,
 *   nexus-api-gateway
 *
 * fraud-service has NO public-facing endpoints (SecurityConfig denies
 * everything outside /internal/** and /actuator/**) — the contract that
 * matters here is exactly that boundary, not a business response shape.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                // This test only provisions Postgres + Redis (Section above).
                // The aggregate /actuator/health status also folds in the Kafka
                // binder (60s timeout against the fake bootstrap-servers below)
                // and Elasticsearch (not provisioned at all here) - neither is
                // part of this test's contract, so exclude them the same way
                // eureka/config-server discovery is excluded above.
                "management.health.binders.enabled=false",
                "management.health.elasticsearch.enabled=false"
        })
@AutoConfigureMockMvc
@Testcontainers
@Tag("contract")
class FraudServiceContractTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("nexus_fraud_test")
                    .withUsername("nexus_test")
                    .withPassword("nexus_test");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "test");

    @Autowired MockMvc mockMvc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @Test
    @DisplayName("CONTRACT: /internal/** without X-Internal-Service header returns 403 FORBIDDEN")
    void internalEndpoint_missingServiceHeader_returns403() throws Exception {
        mockMvc.perform(get("/internal/v1/fraud/metrics"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("CONTRACT: /internal/** with a service not on the allow-list returns 403 FORBIDDEN")
    void internalEndpoint_unknownServiceHeader_returns403() throws Exception {
        mockMvc.perform(get("/internal/v1/fraud/metrics")
                        .header("X-Internal-Service", "some-unknown-service"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("CONTRACT: /internal/** with an allow-listed caller succeeds")
    void internalEndpoint_allowListedServiceHeader_succeeds() throws Exception {
        mockMvc.perform(get("/internal/v1/fraud/metrics")
                        .header("X-Internal-Service", "nexus-transaction-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastHour").exists())
                .andExpect(jsonPath("$.pendingReviewCount").exists());
    }

    @Test
    @DisplayName("CONTRACT: GET decision for unknown transaction returns 404 with ProblemDetail shape")
    void getDecision_unknownTransaction_returns404() throws Exception {
        mockMvc.perform(get("/internal/v1/fraud/decisions/{transactionId}",
                        "00000000-0000-0000-0000-000000000001")
                        .header("X-Internal-Service", "nexus-saga-orchestrator"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("CONTRACT: GET pending reviews returns an array, even when empty")
    void getPendingReviews_returnsArray() throws Exception {
        mockMvc.perform(get("/internal/v1/fraud/decisions/pending-reviews")
                        .header("X-Internal-Service", "nexus-ai-assistant-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("CONTRACT: actuator health does not require X-Internal-Service and returns UP")
    void actuatorHealth_bypassesInternalServiceFilter_returnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
