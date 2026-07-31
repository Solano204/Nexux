package com.nexus.fraud.integration;

import com.nexus.fraud.application.FraudAnalysisService;
import com.nexus.fraud.infrastructure.redis.FraudRedisRepository;
import com.nexus.fraud.domain.model.FraudDecision;
import com.nexus.fraud.domain.model.enums.FraudDecisionOutcome;
import com.nexus.fraud.web.dto.FraudAnalysisRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
class FraudAnalysisIntegrationTest {

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

    @Autowired FraudAnalysisService fraudService;
    // FraudAnalysisService has no public getter for its Redis repository -
    // autowired directly rather than adding a test-only getter to
    // production code.
    @Autowired FraudRedisRepository redisRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test");
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers",
                () -> "localhost:9092");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @Test
    @DisplayName("Hard reject: blacklisted merchant skips AI analysis")
    void hardReject_blacklistedMerchant() {
        String merchantId = "merchant-blacklisted-001";

        // Add to blacklist
        redisRepository.blacklistMerchant(merchantId);

        FraudAnalysisRequest request = new FraudAnalysisRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                new BigDecimal("100.00"), "MXN",
                "PAYMENT", "Purchase",
                merchantId, "Bad Merchant", "7995",
                "192.168.1.100", "device-001",
                true, 365, 10,
                Map.of(), "trace-001"
        );

        FraudDecision decision = fraudService.analyze(request);

        assertThat(decision.decision())
                .isEqualTo(FraudDecisionOutcome.REJECT);
        assertThat(decision.riskScore())
                .isEqualByComparingTo("100");
        assertThat(decision.reasoning())
                .contains("hard rule");
    }

    @Test
    @DisplayName("Idempotent: same transaction returns same decision")
    void idempotent_sameTransaction_returnsSameDecision() {
        String txnId = UUID.randomUUID().toString();
        FraudAnalysisRequest request = buildRequest(txnId, "500.00");

        FraudDecision first = fraudService.analyze(request);
        FraudDecision second = fraudService.analyze(request);

        assertThat(first.transactionId())
                .isEqualTo(second.transactionId());
        assertThat(first.decision())
                .isEqualTo(second.decision());
    }

    private FraudAnalysisRequest buildRequest(
            String txnId, String amount) {
        return new FraudAnalysisRequest(
                txnId, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                new BigDecimal(amount), "MXN",
                "INTERNAL_TRANSFER", "Test",
                null, null, null,
                "192.168.1.100", "device-001",
                true, 365, 50,
                Map.of(), "trace-001"
        );
    }
}
