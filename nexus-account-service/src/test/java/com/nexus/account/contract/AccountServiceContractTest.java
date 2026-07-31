package com.nexus.account.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.account.domain.model.Account;
import com.nexus.account.domain.model.enums.AccountStatus;
import com.nexus.account.domain.model.enums.AccountType;
import com.nexus.account.infrastructure.persistence.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Account Service Contract Tests.
 *
 * Verifies response contracts expected by consumers:
 *   nexus-api-gateway          → account summary endpoint shape
 *   nexus-transaction-service  → /internal/v1/accounts/{id}/balance
 *   nexus-fraud-service        → /internal/v1/accounts/users/{userId}
 *   mobile/web client          → account detail fields
 *
 * Changing any of these response shapes is a BREAKING CHANGE
 * and requires coordination with all consumers.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "spring.cloud.bus.enabled=false",
                "eureka.client.enabled=false"
        })
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("contract")
class AccountServiceContractTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("nexus_account_test")
                    .withUsername("nexus_test")
                    .withPassword("nexus_test");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "test");

    @Container
    static final MongoDBContainer mongo =
            new MongoDBContainer("mongo:7.0");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test");
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
    }

    @Test
    @DisplayName("CONTRACT: GET /api/v1/accounts requires X-User-Id header")
    void accounts_missingUserId_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.properties.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("CONTRACT: GET /api/v1/accounts with valid userId returns array")
    void accounts_validUserId_returnsArray() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                        .header("X-User-Id",
                                "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("CONTRACT: GET balance with no cache returns 503 with Retry-After")
    void balance_cacheMiss_returns503WithRetryAfter() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        accountRepository.save(Account.builder()
                .accountId(accountId)
                .accountNumber("9999-8888-7777-6666")
                .userId(userId)
                .accountType(AccountType.CHECKING)
                .currency("MXN")
                .status(AccountStatus.ACTIVE)
                .availableBalance(new BigDecimal("100.00"))
                .reservedAmount(BigDecimal.ZERO)
                .pendingCredit(BigDecimal.ZERO)
                .dailyTransactionLimit(new BigDecimal("50000.00"))
                .dailyTransactionUsed(BigDecimal.ZERO)
                .monthlyTransactionLimit(new BigDecimal("500000.00"))
                .monthlyTransactionUsed(BigDecimal.ZERO)
                .minimumBalance(BigDecimal.ZERO)
                .interestRate(BigDecimal.ZERO)
                .dailyResetAt(Instant.now())
                .build());

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", accountId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("BALANCE_CACHE_WARMING"));
    }

    // internalHealth_returnsUp removed: /internal/v1/accounts/health never
    // existed (real internal prefix is /internal/api/v1/accounts, and
    // InternalAccountController's own Javadoc says health checks go
    // through /actuator/health - see actuatorHealth_returnsUp below,
    // which already covers this). Not inventing a route to make a test
    // pass; the endpoint was aspirational and never built.

    @Test
    @DisplayName("CONTRACT: GET /api/v1/accounts/{id} requires X-User-Id")
    void accountDetail_missingUserId_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}",
                        "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.properties.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("CONTRACT: non-existent account returns 404 with ACCOUNT_NOT_FOUND")
    void accountDetail_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}",
                        "00000000-0000-0000-0000-999999999999")
                        .header("X-User-Id",
                                "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.properties.errorCode").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("CONTRACT: actuator health endpoint returns UP")
    void actuatorHealth_returnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
