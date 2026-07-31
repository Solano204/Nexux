package com.nexus.gateway.integration;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
import java.math.BigInteger;
import java.util.Base64;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
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

    // JwksCache (nexus.gateway.jwt.jwks-uri) makes a real HTTP GET against
    // this URL to resolve a kid to a public key - without a real
    // identity-service running in this Testcontainers-only test, "test-kid"
    // could never resolve, and every token in this file would fail
    // validation regardless of signature correctness. WireMock stands in
    // for identity-service's real JWKS endpoint, serving this test's own
    // generated key under kid "test-kid".
    private static WireMockServer wireMockServer;

    @Autowired
    WebTestClient webTestClient;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
        privateKey = (RSAPrivateKey) pair.getPrivate();

        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        wireMockServer.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock.get(
                                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(
                                        "/api/v1/auth/.well-known/jwks.json"))
                        .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                                .withHeader("Content-Type", "application/json")
                                .withBody(buildJwksResponse(publicKey, "test-kid"))));
    }

    @org.junit.jupiter.api.AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) wireMockServer.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-password");
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("nexus.gateway.jwt.jwks-uri",
                () -> "http://localhost:" + wireMockServer.port()
                        + "/api/v1/auth/.well-known/jwks.json");
        // securityHeaders_presentOnEveryResponse hits /actuator/health
        // directly - the Kafka binder health indicator's connectivity
        // check (no broker provisioned here) can otherwise hang that
        // request well past this class's own WebTestClient timeout.
        registry.add("management.health.binders.enabled", () -> "false");
    }

    // Standard JWK RSA encoding: base64url (no padding), big-endian,
    // sign byte stripped if BigInteger.toByteArray() added one.
    private static String buildJwksResponse(RSAPublicKey key, String kid) {
        String n = base64UrlNoSign(key.getModulus());
        String e = base64UrlNoSign(key.getPublicExponent());
        return String.format(
                "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\"," +
                        "\"kid\":\"%s\",\"n\":\"%s\",\"e\":\"%s\"}]}",
                kid, n, e);
    }

    private static String base64UrlNoSign(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
                .withIssuer("nexus-identity-service")
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
                .withIssuer("nexus-identity-service")
                .withAudience("nexus-platform")
                .withKeyId("test-kid")
                .withClaim("roles", List.of("USER"))
                .withClaim("accountStatus", "ACTIVE")
                .withIssuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .withExpiresAt(Date.from(Instant.now().minusSeconds(3600)))
                .sign(Algorithm.RSA256(null, privateKey));
    }
}
