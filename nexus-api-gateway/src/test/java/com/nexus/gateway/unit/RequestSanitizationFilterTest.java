package com.nexus.gateway.unit;

import com.nexus.gateway.filter.RequestSanitizationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("unit")
class RequestSanitizationFilterTest {

    private RequestSanitizationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestSanitizationFilter();
    }

    @Test
    @DisplayName("X-User-Id header is stripped from incoming request")
    void filter_stripsXUserId_preventingInjection() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts/me")
                .header("X-User-Id", "injected-user-id")
                .header("Authorization", "Bearer some-token")
                .build();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> capturedExchange =
                new AtomicReference<>();

        GatewayFilterChain chain = ex -> {
            capturedExchange.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // X-User-Id must be gone from the request that reaches downstream
        assertThat(capturedExchange.get().getRequest()
                .getHeaders().getFirst("X-User-Id"))
                .isNull();

        // Authorization header must still be present (not stripped)
        assertThat(capturedExchange.get().getRequest()
                .getHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer some-token");
    }

    @Test
    @DisplayName("X-User-Roles header is stripped")
    void filter_stripsXUserRoles() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts/me")
                .header("X-User-Roles", "ADMIN,USER")
                .build();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured =
                new AtomicReference<>();

        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(captured.get().getRequest()
                .getHeaders().getFirst("X-User-Roles"))
                .isNull();
    }

    @Test
    @DisplayName("X-Gateway-Internal header is stripped")
    void filter_stripsXGatewayInternal() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/internal/v1/fraud/check")
                .header("X-Gateway-Internal", "true")
                .build();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured =
                new AtomicReference<>();

        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(captured.get().getRequest()
                .getHeaders().getFirst("X-Gateway-Internal"))
                .isNull();
    }

    @Test
    @DisplayName("Request with no protected headers passes through unchanged")
    void filter_noProtectedHeaders_passesThrough() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts/me")
                .header("Authorization", "Bearer token")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(request);

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Chain should be called exactly once (no header mutation needed)
        verify(chain, times(1)).filter(any());
    }

    @Test
    @DisplayName("Multiple protected headers are all stripped in one pass")
    void filter_multipleInjectedHeaders_allStripped() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/transactions")
                .header("X-User-Id", "fake-user")
                .header("X-User-Roles", "ADMIN")
                .header("X-Account-Status", "ACTIVE")
                .header("X-Forwarded-User", "hacker")
                .header("Authorization", "Bearer real-token")
                .build();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured =
                new AtomicReference<>();

        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        var headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Id")).isNull();
        assertThat(headers.getFirst("X-User-Roles")).isNull();
        assertThat(headers.getFirst("X-Account-Status")).isNull();
        assertThat(headers.getFirst("X-Forwarded-User")).isNull();
        // Real headers survive
        assertThat(headers.getFirst("Authorization"))
                .isEqualTo("Bearer real-token");
    }

    @Test
    @DisplayName("Filter runs at HIGHEST_PRECEDENCE (before all other filters)")
    void filter_orderIsHighestPrecedence() {
        assertThat(filter.getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
