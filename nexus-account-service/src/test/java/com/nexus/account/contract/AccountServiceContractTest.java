//package com.nexus.account.contract;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Tag;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
//import org.springframework.test.web.servlet.MockMvc;
//import org.testcontainers.containers.GenericContainer;
//import org.testcontainers.containers.PostgreSQLContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
//
///**
// * Account Service Contract Tests.
// *
// * Verifies response contracts expected by consumers:
// *   nexus-api-gateway          → account summary endpoint shape
// *   nexus-transaction-service  → /internal/v1/accounts/{id}/balance
// *   nexus-fraud-service        → /internal/v1/accounts/users/{userId}
// *   mobile/web client          → account detail fields
// *
// * Changing any of these response shapes is a BREAKING CHANGE
// * and requires coordination with all consumers.
// */
//@SpringBootTest(
//        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
//        properties = {
//                "spring.cloud.config.enabled=false",
//                "spring.cloud.bus.enabled=false",
//                "eureka.client.enabled=false"
//        })
//@AutoConfigureMockMvc
//@Testcontainers
//@ActiveProfiles("test")
//@Tag("contract")
//class AccountServiceContractTest {
//
//    @Container
//    static final PostgreSQLContainer<?> postgres =
//            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
//                    .withDatabaseName("nexus_account_test")
//                    .withUsername("nexus_test")
//                    .withPassword("nexus_test");
//
//    @Container
//    static final GenericContainer<?> redis =
//            new GenericContainer<>("redis:7.2-alpine")
//                    .withExposedPorts(6379)
//                    .withCommand("redis-server", "--requirepass", "test");
//
//    @Autowired MockMvc mockMvc;
//    @Autowired ObjectMapper objectMapper;
//
//    @DynamicPropertySource
//    static void configureProperties(DynamicPropertyRegistry registry) {
//        registry.add("spring.datasource.url", postgres::getJdbcUrl);
//        registry.add("spring.datasource.username", postgres::getUsername);
//        registry.add("spring.datasource.password", postgres::getPassword);
//        registry.add("spring.data.redis.host", redis::getHost);
//        registry.add("spring.data.redis.port",
//                () -> redis.getMappedPort(6379));
//        registry.add("spring.data.redis.password", () -> "test");
//        registry.add("spring.ai.openai.api-key", () -> "test");
//    }
//
//    @Test
//    @DisplayName("CONTRACT: GET /api/v1/accounts requires X-User-Id header")
//    void accounts_missingUserId_returns401() throws Exception {
//        mockMvc.perform(get("/api/v1/accounts"))
//                .andExpect(status().isUnauthorized())
//                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
//    }
//
//    @Test
//    @DisplayName("CONTRACT: GET /api/v1/accounts with valid userId returns array")
//    void accounts_validUserId_returnsArray() throws Exception {
//        mockMvc.perform(get("/api/v1/accounts")
//                        .header("X-User-Id",
//                                "00000000-0000-0000-0000-000000000001"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$").isArray());
//    }
//
//    @Test
//    @DisplayName("CONTRACT: GET balance with no cache returns 503 with Retry-After")
//    void balance_cacheMiss_returns503WithRetryAfter() throws Exception {
//        mockMvc.perform(get("/api/v1/accounts/{id}/balance",
//                        "00000000-0000-0000-0000-000000000001"))
//                .andExpect(status().isServiceUnavailable())
//                .andExpect(header().exists("Retry-After"))
//                .andExpect(jsonPath("$.error").value("BALANCE_CACHE_WARMING"));
//    }
//
//    @Test
//    @DisplayName("CONTRACT: /internal/v1 health endpoint returns UP")
//    void internalHealth_returnsUp() throws Exception {
//        mockMvc.perform(get("/internal/v1/accounts/health"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.status").value("UP"))
//                .andExpect(jsonPath("$.service")
//                        .value("nexus-account-service"))
//                .andExpect(jsonPath("$.timestamp").isNotEmpty());
//    }
//
//    @Test
//    @DisplayName("CONTRACT: GET /api/v1/accounts/{id} requires X-User-Id")
//    void accountDetail_missingUserId_returns401() throws Exception {
//        mockMvc.perform(get("/api/v1/accounts/{id}",
//                        "00000000-0000-0000-0000-000000000001"))
//                .andExpect(status().isUnauthorized())
//                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
//    }
//
//    @Test
//    @DisplayName("CONTRACT: non-existent account returns 404 with ACCOUNT_NOT_FOUND")
//    void accountDetail_nonExistent_returns404() throws Exception {
//        mockMvc.perform(get("/api/v1/accounts/{id}",
//                        "00000000-0000-0000-0000-999999999999")
//                        .header("X-User-Id",
//                                "00000000-0000-0000-0000-000000000001"))
//                .andExpect(status().isNotFound())
//                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));
//    }
//
//    @Test
//    @DisplayName("CONTRACT: actuator health endpoint returns UP")
//    void actuatorHealth_returnsUp() throws Exception {
//        mockMvc.perform(get("/actuator/health"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.status").value("UP"));
//    }
//}