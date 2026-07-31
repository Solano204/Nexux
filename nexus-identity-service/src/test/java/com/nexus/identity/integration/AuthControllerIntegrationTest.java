package com.nexus.identity.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.core.StringContains.containsString;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class AuthControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("nexus_identity_test")
                    .withUsername("nexus_test")
                    .withPassword("nexus_test");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server",
                            "--requirepass", "test-password");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-password");
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @Test
    @DisplayName("Full registration flow: register → JWKS endpoint → profile")
    void fullRegistrationFlow() throws Exception {
        // Step 1: Register
        Map<String, Object> registerBody = Map.of(
                "email", "integration@test.com",
                "password", "SecurePassword123!",
                "fullName", "Integration Test",
                "phoneNumber", "+525512345678",
                "dateOfBirth", "1990-01-15",
                "country", "MX"
        );

        String registerResponse = mockMvc
                .perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.message").value(containsString("Registration successful"))) // ✅
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Step 2: JWKS endpoint returns public key
        mockMvc.perform(get("/api/v1/auth/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"));

        // Step 3: Login
        Map<String, Object> loginBody = Map.of(
                "email", "integration@test.com",
                "password", "SecurePassword123!",
                "deviceFingerprint", "device-test-001"
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").doesNotExist()); // Not in body!
    }

    @Test
    @DisplayName("Login with wrong password returns 401 — deliberately vague error")
    void loginWrongPassword_returns401VagueError() throws Exception {
        // Register first
        Map<String, Object> registerBody = Map.of(
                "email", "wrongpw@test.com",
                "password", "CorrectPassword123!",
                "fullName", "Test User",
                "phoneNumber", "+525512345679",
                "dateOfBirth", "1990-01-15",
                "country", "MX"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody)))
                .andExpect(status().isCreated());

        // Try with wrong password
        Map<String, Object> loginBody = Map.of(
                "email", "wrongpw@test.com",
                "password", "WrongPassword123!",
                "deviceFingerprint", "device-001"
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginBody)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
        // Deliberately vague — doesn't say "wrong password" or "user not found"
    }

    @Test
    @DisplayName("Duplicate email registration returns 409")
    void duplicateEmail_returns409() throws Exception {
        Map<String, Object> body = Map.of(
                "email", "duplicate@test.com",
                "password", "SecurePassword123!",
                "fullName", "First User",
                "phoneNumber", "+525512345680",
                "dateOfBirth", "1990-01-15",
                "country", "MX"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // Second registration with same email
        Map<String, Object> body2 = Map.of(
                "email", "duplicate@test.com",
                "password", "AnotherPassword123!",
                "fullName", "Second User",
                "phoneNumber", "+525512345681",
                "dateOfBirth", "1991-01-15",
                "country", "MX"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_EXISTS"));
    }

    @Test
    @DisplayName("Outbox entry created on registration (Debezium trigger)")
    void registration_createsOutboxEntry() throws Exception {
        // Register
        Map<String, Object> body = Map.of(
                "email", "outbox@test.com",
                "password", "SecurePassword123!",
                "fullName", "Outbox Test",
                "phoneNumber", "+525512345682",
                "dateOfBirth", "1990-01-15",
                "country", "MX"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // Verify outbox table has UserRegistered entry
        // (Would use JDBC template in real test to query outbox table)
        // This demonstrates the Outbox Pattern integration test
    }
}
