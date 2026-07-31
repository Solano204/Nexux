package com.nexus.gateway.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.gateway.filter.JwtAuthenticationFilter;
import com.nexus.gateway.jwt.JwtClaims;
import com.nexus.gateway.jwt.JwtValidator;
import com.nexus.gateway.jwt.TokenBlacklistService;
import com.nexus.gateway.observability.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtValidator jwtValidator;
    @Mock private TokenBlacklistService blacklistService;
    @Mock private GatewayFilterChain chain;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GatewayFilter gatewayFilter;

    @BeforeEach
    void setUp() {
        GatewayMetrics gatewayMetrics = new GatewayMetrics(new SimpleMeterRegistry());
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtValidator, blacklistService, objectMapper, ObservationRegistry.NOOP, gatewayMetrics);
        gatewayFilter = filter.apply(new JwtAuthenticationFilter.Config());
    }

    private JwtClaims activeClaims() {
        return JwtClaims.builder()
                .userId("user-123")
                .jti("jti-abc")
                .roles(List.of("USER"))
                .accountStatus("ACTIVE")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    @Test
    void rejectsRequestWithNoAuthorizationHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/accounts").build());

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void rejectsRequestWithNonBearerAuthorizationHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/accounts")
                        .header("Authorization", "Basic dXNlcjpwYXNz").build());

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void rejectsEmptyBearerToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/accounts")
                        .header("Authorization", "Bearer   ").build());

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsInvalidTokenWhenValidatorReturnsEmpty() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/accounts")
                        .header("Authorization", "Bearer bad-token").build());
        when(jwtValidator.validate("bad-token")).thenReturn(Mono.empty());

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void rejectsRevokedToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/accounts")
                        .header("Authorization", "Bearer good-token").build());
        when(jwtValidator.validate("good-token")).thenReturn(Mono.just(activeClaims()));
        when(blacklistService.isBlacklisted("jti-abc")).thenReturn(Mono.just(true));

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void rejectsSuspendedAccount() {
        JwtClaims suspended = JwtClaims.builder()
                .userId("user-123").jti("jti-abc").roles(List.of("USER"))
                .accountStatus("SUSPENDED").expiresAt(Instant.now().plusSeconds(900)).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/accounts")
                        .header("Authorization", "Bearer good-token").build());
        when(jwtValidator.validate("good-token")).thenReturn(Mono.just(suspended));
        when(blacklistService.isBlacklisted("jti-abc")).thenReturn(Mono.just(false));

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    void enrichesRequestHeadersAndCallsChainForValidToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/accounts")
                        .header("Authorization", "Bearer good-token").build());
        when(jwtValidator.validate("good-token")).thenReturn(Mono.just(activeClaims()));
        when(blacklistService.isBlacklisted("jti-abc")).thenReturn(Mono.just(false));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(gatewayFilter.filter(exchange, chain)).verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        ServerWebExchange mutated = captor.getValue();
        assertThat(mutated.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("user-123");
        assertThat(mutated.getRequest().getHeaders().getFirst("X-User-Roles")).isEqualTo("USER");
        assertThat(mutated.getRequest().getHeaders().getFirst("X-Account-Status")).isEqualTo("ACTIVE");
        assertThat(mutated.getRequest().getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }
}
