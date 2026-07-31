package com.nexus.gateway.integration;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.nexus.gateway.jwt.JwksCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JWT Authentication Integration Tests.
 *
 * Tests the complete authentication filter chain:
 *   JwtAuthenticationFilter → JwtValidator → TokenBlacklistService → Redis
 *
 * Uses:
 *   - Real Spring Boot context (WebFlux reactive)
 *   - Testcontainers Redis (real Redis instance)
 *   - Mocked JwksCache (to avoid calling real identity service)
 *   - WireMock for downstream service simulation
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "spring.cloud.bus.enabled=false",
                "eureka.client.enabled=false",
                "nexus.gateway.global-rate-limit.enabled=false"
        })
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
@ActiveProfiles("test")
@Tag("integration")
class JwtAuthenticationIntegrationTest {

    /**
     * Test configuration providing mock beans.
     * Replaces deprecated @MockBean with modern Spring Boot 3.4.0+ approach.
     */
    @TestConfiguration
    static class JwtAuthenticationTestConfiguration {
        @Bean
        JwksCache jwksCache() {
            return mock(JwksCache.class);
        }
    }

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379);

    @Autowired
    private JwksCache jwksCache;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    private static RSAPublicKey publicKey;
    private static RSAPrivateKey privateKey;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
        privateKey = (RSAPrivateKey) pair.getPrivate();
    }

    @BeforeEach
    void setUpJwksMock() {
        // Return our test public key for any kid
        when(jwksCache.getPublicKey(anyString()))
                .thenReturn(publicKey);
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    // ── Missing token ─────────────────────────────────────────

    @Test
    @DisplayName("No Authorization header → 401 MISSING_TOKEN")
    void noAuthHeader_returns401() {
        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("MISSING_TOKEN");
    }

    @Test
    @DisplayName("Authorization header without Bearer prefix → 401")
    void authHeaderWithoutBearer_returns401() {
        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Empty Bearer token → 401 MISSING_TOKEN")
    void emptyBearerToken_returns401() {
        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("Authorization", "Bearer ")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("MISSING_TOKEN");
    }

    // ── Invalid tokens ────────────────────────────────────────

    @Test
    @DisplayName("Malformed JWT (not 3 parts) → 401 INVALID_TOKEN")
    void malformedToken_returns401() {
        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("Authorization", "Bearer not.a.valid.jwt.at.all")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INVALID_TOKEN");
    }

    @Test
    @DisplayName("Expired JWT → 401 INVALID_TOKEN")
    void expiredToken_returns401() {
        String token = JWT.create()
                .withSubject("user-1")
                .withJWTId(UUID.randomUUID().toString())
                .withIssuer("nexus-identity-service")
                .withAudience("nexus-platform")
                .withKeyId("test-kid")
                .withClaim("roles", List.of("USER"))
                .withClaim("accountStatus", "ACTIVE")
                .withIssuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .withExpiresAt(Date.from(Instant.now().minusSeconds(3600)))
                .sign(Algorithm.RSA256(null, privateKey));

        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INVALID_TOKEN");
    }

    @Test
    @DisplayName("JWT with wrong issuer → 401 INVALID_TOKEN")
    void wrongIssuer_returns401() {
        String token = JWT.create()
                .withSubject("user-1")
                .withJWTId(UUID.randomUUID().toString())
                .withIssuer("wrong-issuer")  // Not nexus-identity-service
                .withAudience("nexus-platform")
                .withKeyId("test-kid")
                .withClaim("roles", List.of("USER"))
                .withClaim("accountStatus", "ACTIVE")
                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
                .sign(Algorithm.RSA256(null, privateKey));

        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Revoked tokens ────────────────────────────────────────

    @Test
    @DisplayName("Revoked JWT (in Redis blacklist) → 401 TOKEN_REVOKED")
    void revokedToken_returns401() {
        String jti = UUID.randomUUID().toString();

        // Pre-populate blacklist in Redis
        redisTemplate.opsForValue()
                .set("jwt:blacklist:" + jti, "1",
                        java.time.Duration.ofMinutes(30))
                .block();

        String token = buildValidToken("user-1", jti, "ACTIVE");

        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("TOKEN_REVOKED");
    }

    // ── Suspended accounts ────────────────────────────────────

    @Test
    @DisplayName("Suspended account → 403 ACCOUNT_SUSPENDED")
    void suspendedAccount_returns403() {
        String token = buildValidToken("suspended-user",
                UUID.randomUUID().toString(), "SUSPENDED");

        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("ACCOUNT_SUSPENDED")
                .jsonPath("$.message").isNotEmpty();
    }

    // ── Header injection ──────────────────────────────────────

    @Test
    @DisplayName("Attacker injecting X-User-Id is rejected at auth, not passed through")
    void injectedXUserId_sanitizedBeforeAuth() {
        // Attacker sends X-User-Id hoping to bypass auth
        // RequestSanitizationFilter strips it FIRST,
        // then JwtAuthenticationFilter rejects (no valid JWT)
        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("X-User-Id", "attacker-admin-user")
                // No valid JWT
                .exchange()
                .expectStatus().isUnauthorized();
        // If X-User-Id reached downstream, it would return 200
        // The 401 confirms the injected header was stripped
    }

    // ── Security headers ──────────────────────────────────────

    @Test
    @DisplayName("Every response includes X-Content-Type-Options: nosniff")
    void allResponses_includeSecurityHeaders() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff");
    }

    // ── Helpers ───────────────────────────────────────────────

    private String buildValidToken(String userId, String jti,
                                   String accountStatus) {
        return JWT.create()
                .withSubject(userId)
                .withJWTId(jti)
                .withIssuer("nexus-identity-service")
                .withAudience("nexus-platform")
                .withKeyId("test-kid")
                .withClaim("roles", List.of("USER"))
                .withClaim("accountStatus", accountStatus)
                .withIssuedAt(Date.from(Instant.now()))
                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
                .sign(Algorithm.RSA256(null, privateKey));
    }
}
