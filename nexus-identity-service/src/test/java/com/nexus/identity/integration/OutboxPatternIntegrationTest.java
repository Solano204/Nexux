//package com.nexus.identity.integration;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.nexus.identity.infrastructure.persistence.OutboxRepository;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Tag;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.http.MediaType;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
//import org.springframework.test.web.servlet.MockMvc;
//import org.testcontainers.containers.GenericContainer;
//import org.testcontainers.containers.PostgreSQLContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//
//import java.util.List;
//import java.util.Map;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
///**
// * Outbox Pattern Integration Test.
// *
// * Verifies that domain events are written to the outbox table
// * in the SAME transaction as the domain object changes.
// *
// * The outbox pattern guarantees:
// *   domain_write + outbox_write = one atomic PostgreSQL transaction
// * If either fails, both rollback → no event without state change
// * If both succeed → Debezium reads WAL → publishes to Kafka
// *
// * This test queries the outbox table directly via JPA to verify
// * that events were written. In production, Debezium reads the
// * PostgreSQL WAL and publishes to Kafka without polling this table.
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
//@Tag("integration")
//class OutboxPatternIntegrationTest {
//
//    @Container
//    static final PostgreSQLContainer<?> postgres =
//            new PostgreSQLContainer<>("postgres:16-alpine")
//                    .withDatabaseName("nexus_identity_test")
//                    .withUsername("nexus_test")
//                    .withPassword("nexus_test");
//
//    @Container
//    static final GenericContainer<?> redis =
//            new GenericContainer<>("redis:7.2-alpine")
//                    .withExposedPorts(6379)
//                    .withCommand("redis-server", "--requirepass", "test-password");
//
//    @Autowired
//    private MockMvc mockMvc;
//
//    @Autowired
//    private ObjectMapper objectMapper;
//
//    @Autowired
//    private OutboxRepository outboxRepository;
//
//    @DynamicPropertySource
//    static void configureProperties(DynamicPropertyRegistry registry) {
//        registry.add("spring.datasource.url", postgres::getJdbcUrl);
//        registry.add("spring.datasource.username", postgres::getUsername);
//        registry.add("spring.datasource.password", postgres::getPassword);
//        registry.add("spring.data.redis.host", redis::getHost);
//        registry.add("spring.data.redis.port",
//                () -> redis.getMappedPort(6379));
//        registry.add("spring.data.redis.password", () -> "test-password");
//        registry.add("spring.ai.openai.api-key", () -> "test-key");
//    }
//
//    @Test
//    @DisplayName("OUTBOX: UserRegistered event written atomically with user row")
//    void register_writesUserRegisteredToOutbox() throws Exception {
//        long outboxCountBefore = outboxRepository.count();
//
//        Map<String, Object> body = Map.of(
//                "email", "outbox-test-1@nexusbank.com",
//                "password", "SecurePassword123!",
//                "fullName", "Outbox Test",
//                "phoneNumber", "+525512341001",
//                "dateOfBirth", "1990-01-15",
//                "country", "MX"
//        );
//
//        mockMvc.perform(post("/api/v1/auth/register")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(body)))
//                .andExpect(status().isCreated());
//
//        // One new outbox entry created
//        long outboxCountAfter = outboxRepository.count();
//        assertThat(outboxCountAfter).isEqualTo(outboxCountBefore + 1);
//
//        // The new entry is UserRegistered type
//        var entries = outboxRepository.findAll();
//        var newEntry = entries.stream()
//                .filter(e -> "UserRegistered".equals(e.getEventType()))
//                .max(java.util.Comparator.comparing(
//                        e -> e.getCreatedAt()))
//                .orElseThrow();
//
//        assertThat(newEntry.getAggregateType()).isEqualTo("USER");
//        assertThat(newEntry.getEventType()).isEqualTo("UserRegistered");
//        assertThat(newEntry.getAggregateId()).isNotNull();
//        assertThat(newEntry.getPayload()).isNotNull();
//        assertThat(newEntry.getPayload().get("email").asText())
//                .isEqualTo("outbox-test-1@nexusbank.com");
//    }
//
//    @Test
//    @DisplayName("OUTBOX: LoginSuccessful event written after successful login")
//    void login_writesLoginSuccessfulToOutbox() throws Exception {
//        // Register first
//        Map<String, Object> registerBody = Map.of(
//                "email", "outbox-login@nexusbank.com",
//                "password", "SecurePassword123!",
//                "fullName", "Outbox Login Test",
//                "phoneNumber", "+525512341002",
//                "dateOfBirth", "1990-01-15",
//                "country", "MX"
//        );
//        mockMvc.perform(post("/api/v1/auth/register")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(registerBody)))
//                .andExpect(status().isCreated());
//
//        long outboxCountAfterRegister = outboxRepository.count();
//
//        // Login
//        Map<String, Object> loginBody = Map.of(
//                "email", "outbox-login@nexusbank.com",
//                "password", "SecurePassword123!",
//                "deviceFingerprint", "test-device"
//        );
//        mockMvc.perform(post("/api/v1/auth/login")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(loginBody)))
//                .andExpect(status().isOk());
//
//        // One more outbox entry: LoginSuccessful
//        long outboxCountAfterLogin = outboxRepository.count();
//        assertThat(outboxCountAfterLogin)
//                .isGreaterThan(outboxCountAfterRegister);
//
//        // Verify LoginSuccessful event exists
//        boolean hasLoginEvent = outboxRepository.findAll().stream()
//                .anyMatch(e -> "LoginSuccessful".equals(e.getEventType()));
//        assertThat(hasLoginEvent).isTrue();
//    }
//
//    @Test
//    @DisplayName("OUTBOX: Failed registration leaves NO outbox entry (atomicity)")
//    void register_duplicateEmail_leavesNoOutboxEntry() throws Exception {
//        // Register once
//        Map<String, Object> body = Map.of(
//                "email", "outbox-duplicate@nexusbank.com",
//                "password", "SecurePassword123!",
//                "fullName", "First Registration",
//                "phoneNumber", "+525512341003",
//                "dateOfBirth", "1990-01-15",
//                "country", "MX"
//        );
//        mockMvc.perform(post("/api/v1/auth/register")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(body)))
//                .andExpect(status().isCreated());
//
//        long countAfterFirst = outboxRepository.count();
//
//        // Try to register again with same email — should fail
//        Map<String, Object> body2 = Map.of(
//                "email", "outbox-duplicate@nexusbank.com",
//                "password", "AnotherPassword123!",
//                "fullName", "Second Registration",
//                "phoneNumber", "+525512341004",
//                "dateOfBirth", "1992-05-10",
//                "country", "MX"
//        );
//        mockMvc.perform(post("/api/v1/auth/register")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(body2)))
//                .andExpect(status().isConflict());
//
//        // No new outbox entry — transaction rolled back
//        long countAfterDuplicate = outboxRepository.count();
//        assertThat(countAfterDuplicate).isEqualTo(countAfterFirst);
//    }
//
//    @Test
//    @DisplayName("OUTBOX: payload contains all required fields for Debezium")
//    void outboxPayload_containsAllRequiredFields() throws Exception {
//        Map<String, Object> body = Map.of(
//                "email", "outbox-payload@nexusbank.com",
//                "password", "SecurePassword123!",
//                "fullName", "Payload Checker",
//                "phoneNumber", "+525512341005",
//                "dateOfBirth", "1990-01-15",
//                "country", "MX"
//        );
//
//        mockMvc.perform(post("/api/v1/auth/register")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(objectMapper.writeValueAsString(body)))
//                .andExpect(status().isCreated());
//
//        var entry = outboxRepository.findAll().stream()
//                .filter(e -> "UserRegistered".equals(e.getEventType()))
//                .filter(e -> {
//                    var emailNode = e.getPayload().get("email");
//                    return emailNode != null &&
//                            "outbox-payload@nexusbank.com"
//                                    .equals(emailNode.asText());
//                })
//                .findFirst()
//                .orElseThrow(() ->
//                        new AssertionError("UserRegistered entry not found"));
//
//        // Debezium-required fields
//        assertThat(entry.getOutboxId()).isNotNull();
//        assertThat(entry.getAggregateType()).isNotBlank();
//        assertThat(entry.getAggregateId()).isNotNull();
//        assertThat(entry.getEventType()).isNotBlank();
//        assertThat(entry.getPayload()).isNotNull();
//        assertThat(entry.getCreatedAt()).isNotNull();
//        assertThat(entry.getProcessedAt()).isNull(); // Not yet processed
//
//        // Domain event payload
//        var payload = entry.getPayload();
//        assertThat(payload.has("userId")).isTrue();
//        assertThat(payload.has("email")).isTrue();
//        assertThat(payload.has("fullName")).isTrue();
//        assertThat(payload.has("country")).isTrue();
//        assertThat(payload.has("createdAt")).isTrue();
//    }
//}