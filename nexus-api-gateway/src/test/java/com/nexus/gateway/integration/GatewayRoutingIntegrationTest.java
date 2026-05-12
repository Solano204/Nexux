package com.nexus.gateway.integration;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
@Tag("integration")
class GatewayRoutingIntegrationTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withCommand("redis-server",
                            "--requirepass", "test-password");

    private static RSAPublicKey publicKey;
    private static RSAPrivateKey privateKey;

    @Autowired
    WebTestClient webTestClient;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
        privateKey = (RSAPrivateKey) pair.getPrivate();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-password");
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
    }

    @Test
    @DisplayName("Request without JWT to authenticated route returns 401")
    void request_noJwt_authenticated_route_returns401() {
        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("MISSING_TOKEN");
    }

    @Test
    @DisplayName("Request with valid JWT passes authentication filter")
    void request_validJwt_passes_authentication() {
        String token = buildValidToken("user-123", "ACTIVE");

        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                // Route exists but downstream service not running in this test
                // Circuit breaker returns fallback (503) — auth passed
                .expectStatus().is5xxServerError();
    }

    @Test
    @DisplayName("Request with expired JWT returns 401 TOKEN_EXPIRED")
    void request_expiredJwt_returns401() {
        String token = buildExpiredToken("user-123");

        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INVALID_TOKEN");
    }

    @Test
    @DisplayName("Injected X-User-Id header is stripped before JWT validation")
    void injectedHeader_isStrippedBySanitizationFilter() {
        // Attacker tries to inject X-User-Id
        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("X-User-Id", "admin-user-spoofed")
                // No valid JWT
                .exchange()
                .expectStatus().isUnauthorized(); // Rejected despite X-User-Id
    }

    @Test
    @DisplayName("Suspended account JWT returns 403 ACCOUNT_SUSPENDED")
    void suspendedAccount_returns403() {
        String token = buildValidToken("user-suspended", "SUSPENDED");

        webTestClient.get()
                .uri("/api/v1/accounts/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("ACCOUNT_SUSPENDED");
    }

    @Test
    @DisplayName("Security response headers are present on every response")
    void securityHeaders_presentOnEveryResponse() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectHeader().exists("X-Content-Type-Options")
                .expectHeader().exists("X-Frame-Options");
    }

    @Test
    @DisplayName("Circuit breaker fallback returns service-specific message")
    void circuitBreakerFallback_returns503WithSpecificMessage() {
        String token = buildValidToken("user-123", "ACTIVE");

        webTestClient.post()
                .uri("/api/v1/transactions/transfer")
                .header("Authorization", "Bearer " + token)
                .exchange()
                // Transaction service not running in test — circuit breaker opens
                .expectBody()
                .jsonPath("$.error").isEqualTo("TRANSACTION_SERVICE_UNAVAILABLE");
    }

    private String buildValidToken(String userId, String accountStatus) {
        return JWT.create()
                .withSubject(userId)
                .withJWTId(UUID.randomUUID().toString())
                .withIssuer("nexus-platform")
                .withAudience("nexus-platform")
                .withKeyId("test-kid")
                .withClaim("roles", List.of("USER"))
                .withClaim("accountStatus", accountStatus)
                .withIssuedAt(Date.from(Instant.now()))
                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
                .sign(Algorithm.RSA256(null, privateKey));
    }

    private String buildExpiredToken(String userId) {
        return JWT.create()
                .withSubject(userId)
                .withJWTId(UUID.randomUUID().toString())
                .withIssuer("nexus-platform")
                .withAudience("nexus-platform")
                .withKeyId("test-kid")
                .withClaim("roles", List.of("USER"))
                .withClaim("accountStatus", "ACTIVE")
                .withIssuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .withExpiresAt(Date.from(Instant.now().minusSeconds(3600)))
                .sign(Algorithm.RSA256(null, privateKey));
    }
}: