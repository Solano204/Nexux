package com.nexus.gateway.unit;

import com.nexus.gateway.ratelimit.UserIdKeyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

@Tag("unit")
class UserIdKeyResolverTest {

    private UserIdKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new UserIdKeyResolver();
    }

    @Test
    @DisplayName("Resolves to user:{userId} when X-User-Id header is present")
    void resolve_withUserId_returnsUserPrefixedKey() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts/me")
                .header("X-User-Id", "abc-123-user")
                .build();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(request);

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("user:abc-123-user")
                .verifyComplete();
    }

    @Test
    @DisplayName("Falls back to IP when X-User-Id header is missing")
    void resolve_withoutUserId_fallsBackToIp() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts/me")
                .remoteAddress(
                        new java.net.InetSocketAddress("192.168.1.100", 0))
                .build();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(request);

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("192.168.1.100")
                .verifyComplete();
    }

    @Test
    @DisplayName("Falls back to IP when X-User-Id header is blank")
    void resolve_blankUserId_fallsBackToIp() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts/me")
                .header("X-User-Id", "   ")
                .remoteAddress(
                        new java.net.InetSocketAddress("10.0.0.5", 0))
                .build();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(request);

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("10.0.0.5")
                .verifyComplete();
    }

    @Test
    @DisplayName("Uses X-Forwarded-For IP when present for fallback")
    void resolve_withForwardedFor_usesFirstIp() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts/me")
                // No X-User-Id — falls back to IP
                .header("X-Forwarded-For", "203.0.113.5, 10.0.0.1")
                .build();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(request);

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("203.0.113.5")
                .verifyComplete();
    }

    @Test
    @DisplayName("Returns user key with UUID userId")
    void resolve_uuidUserId_returnsCorrectKey() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/transactions")
                .header("X-User-Id", uuid)
                .build();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(request);

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("user:" + uuid)
                .verifyComplete();
    }
}