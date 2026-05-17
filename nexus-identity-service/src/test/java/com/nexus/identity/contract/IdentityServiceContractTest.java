package com.nexus.identity.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Identity Service Contract Tests — consumer-driven contracts.
 *
 * Verifies that the API responses match the contracts expected by:
 *   nexus-api-gateway     → JWT validation endpoints, JWKS
 *   nexus-account-service → /internal/v1/users/{userId}/identity
 *   nexus-fraud-service   → /internal/v1/users/{userId}/identity
 *   nexus-auth-lambda     → /internal/v1/users/{userId}/kyc/status
 *
 * Contract: response structure must NOT change without version bump.
 * Breaking these contracts = runtime failures in other services.
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
class IdentityServiceContractTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("nexus_identity_test")
                    .withUsername("nexus_test")
                    .withPassword("nexus_test");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "test-password");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-password");
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    // ── CONTRACT: JWKS endpoint (consumed by nexus-api-gateway) ──

    @Test
    @DisplayName("CONTRACT: JWKS endpoint returns RFC 7517 compliant response")
    void jwks_returnsRfc7517CompliantResponse() throws Exception {
        mockMvc.perform(get("/api/v1/auth/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // Required by RFC 7517 JWK Set
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys").isNotEmpty())
                // Each key must have these fields (gateway validates all)
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].e").isNotEmpty());
    }

    @Test
    @DisplayName("CONTRACT: JWKS endpoint has Cache-Control header")
    void jwks_hasCacheControlHeader() throws Exception {
        mockMvc.perform(get("/api/v1/auth/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=3600")));
    }

    // ── CONTRACT: Register response (consumed by mobile/web client) ──

    @Test
    @DisplayName("CONTRACT: register response contains userId and message")
    void register_responseContractFieldsPresent() throws Exception {
        Map<String, Object> body = Map.of(
                "email", "contract-test@nexusbank.com",
                "password", "SecurePassword123!",
                "fullName", "Contract Test User",
                "phoneNumber", "+525512341010",
                "dateOfBirth", "1990-01-15",
                "country", "MX"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                // Contract fields that consumers depend on
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                // refreshToken must NOT be in registration response
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    // ── CONTRACT: Login response (consumed by mobile/web client) ──

    @Test
    @DisplayName("CONTRACT: login response matches token contract")
    void login_responseContractFieldsPresent() throws Exception {
        // Register first
        Map<String, Object> regBody = Map.of(
                "email", "contract-login@nexusbank.com",
                "password", "SecurePassword123!",
                "fullName", "Contract Login",
                "phoneNumber", "+525512341011",
                "dateOfBirth", "1990-01-15",
                "country", "MX"
        );
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regBody)))
                .andExpect(status().isCreated());

        Map<String, Object> loginBody = Map.of(
                "email", "contract-login@nexusbank.com",
                "password", "SecurePassword123!",
                "deviceFingerprint", "contract-device"
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginBody)))
                .andExpect(status().isOk())
                // Required fields that mobile app reads
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.roles").isArray())
                // Refresh token in HttpOnly cookie, NOT in response body
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    // ── CONTRACT: Error responses (consumed by all API clients) ──

    @Test
    @DisplayName("CONTRACT: validation error response has fieldErrors map")
    void validationError_responseHasFieldErrors() throws Exception {
        Map<String, Object> badBody = Map.of(
                "email", "not-an-email",     // Invalid
                "password", "short",          // Too short
                "fullName", "",               // Blank
                "phoneNumber", "123",         // Invalid format
                "country", "MEX"              // Too long (max 2)
        );

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("CONTRACT: duplicate email returns 409 with EMAIL_EXISTS error")
    void duplicateEmail_returns409EmailExists() throws Exception {
        Map<String, Object> body = Map.of(
                "email", "contract-duplicate@nexusbank.com",
                "password", "SecurePassword123!",
                "fullName", "First User",
                "phoneNumber", "+525512341012",
                "dateOfBirth", "1990-01-15",
                "country", "MX"
        );
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // Second registration — same email
        Map<String, Object> body2 = Map.of(
                "email", "contract-duplicate@nexusbank.com",
                "password", "AnotherPassword123!",
                "fullName", "Second User",
                "phoneNumber", "+525512341013",
                "dateOfBirth", "1991-01-15",
                "country", "MX"
        );
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body2)))
                .andExpect(status().isConflict())
                // account-service and fraud-service parse this field
                .andExpect(jsonPath("$.error").value("EMAIL_EXISTS"));
    }

    // ── CONTRACT: Internal endpoints (consumed by other services) ──

    @Test
    @DisplayName("CONTRACT: /internal/v1/users/{id}/kyc/status response fields")
    void internalKycStatus_responseContractFieldsPresent() throws Exception {
        // Register a user
        Map<String, Object> body = Map.of(
                "email", "internal-kyc@nexusbank.com",
                "password", "SecurePassword123!",
                "fullName", "Internal KYC Test",
                "phoneNumber", "+525512341014",
                "dateOfBirth", "1990-01-15",
                "country", "MX"
        );
        String regResponse = mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String userId = objectMapper.readTree(regResponse)
                .get("userId").asText();

        // Check internal KYC status endpoint (as auth-lambda would call it)
        mockMvc.perform(get("/internal/v1/users/{userId}/kyc/status", userId))
                .andExpect(status().isOk())
                // auth-lambda reads these fields specifically
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.kycVerified").isBoolean())
                .andExpect(jsonPath("$.accountStatus").isNotEmpty())
                .andExpect(jsonPath("$.kycDecision").isNotEmpty());
    }
}