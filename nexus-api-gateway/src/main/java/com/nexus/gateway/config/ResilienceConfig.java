package com.nexus.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

/**
 * Resilience Configuration — Programmatic Resilience4j setup.
 *
 * Circuit Breaker instances are also configured in application.yml.
 * This class provides:
 *   1. Programmatic CircuitBreakerRegistry with shared defaults
 *   2. Micrometer metric binding for all circuit breakers
 *   3. Event listeners that log state transitions to Slf4j
 *   4. Custom exception predicates for financial operations
 *
 * Circuit breaker state machine:
 *   CLOSED → OPEN: failure rate exceeds threshold over sliding window
 *   OPEN → HALF_OPEN: after waitDurationInOpenState (30s default)
 *   HALF_OPEN → CLOSED: permitted calls succeed
 *   HALF_OPEN → OPEN: permitted calls fail
 *
 * Financial platform tuning:
 *   - Transaction/Account: conservative (50% failure rate, 30s wait)
 *   - AI Assistant: lenient (60% failure rate, 60s wait, LLMs are slow)
 *   - Analytics: tolerant (70%, non-critical for transactions)
 *
 * All circuit breaker state changes emit:
 *   - Micrometer metrics (resilience4j.circuitbreaker.state)
 *   - Slf4j WARN log (for operations alerting)
 *   - CloudWatch metric via GatewayMetrics (AWS-side visibility)
 */
@Slf4j
@Configuration
public class ResilienceConfig {

    /**
     * Shared default CircuitBreakerConfig.
     * Instances in application.yml use "base-config: default"
     * which references this programmatic default.
     */
    @Bean
    public CircuitBreakerConfig defaultCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                // Sliding window: count-based (50 calls)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(50)
                .minimumNumberOfCalls(10)

                // Open circuit when 50% of calls fail
                .failureRateThreshold(50.0f)

                // Open circuit when 80% of calls are slow (>2s)
                .slowCallRateThreshold(80.0f)
                .slowCallDurationThreshold(Duration.ofSeconds(2))

                // Stay OPEN for 30 seconds before trying again
                .waitDurationInOpenState(Duration.ofSeconds(30))

                // Test 5 calls in HALF_OPEN state
                .permittedNumberOfCallsInHalfOpenState(5)

                // Auto-transition OPEN→HALF_OPEN after wait duration
                .automaticTransitionFromOpenToHalfOpenEnabled(true)

                // Record as failures: network issues, server errors
                .recordExceptions(
                        IOException.class,
                        ConnectException.class,
                        SocketTimeoutException.class,
                        org.springframework.web.server.ResponseStatusException.class
                )

                // Do NOT record as failures: business logic errors
                // 4xx client errors are NOT circuit breaker failures
                .ignoreExceptions(
                        com.nexus.gateway.filter.JwtAuthenticationFilter.AuthException.class
                )

                .build();
    }

    /**
     * CircuitBreakerRegistry — central registry for all circuit breakers.
     * Spring Cloud Gateway's CircuitBreaker filter uses this registry
     * to look up circuit breakers by name.
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            CircuitBreakerConfig defaultConfig) {

        CircuitBreakerRegistry registry =
                CircuitBreakerRegistry.of(defaultConfig);

        // Register service-specific overrides programmatically
        // These complement/override the application.yml configs

        // AI Assistant — very lenient (LLM inference can take 30s+)
        registry.addConfiguration("ai-assistant-service",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(20)
                        .failureRateThreshold(60.0f)
                        .slowCallDurationThreshold(Duration.ofSeconds(30))
                        .waitDurationInOpenState(Duration.ofSeconds(60))
                        .minimumNumberOfCalls(5)
                        .build());

        // Transaction service — most conservative (financial critical)
        registry.addConfiguration("transaction-service",
                CircuitBreakerConfig.from(defaultConfig)
                        .failureRateThreshold(50.0f)
                        .slowCallDurationThreshold(Duration.ofSeconds(2))
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .build());

        // Analytics — tolerant (non-critical for core operations)
        registry.addConfiguration("analytics-service",
                CircuitBreakerConfig.from(defaultConfig)
                        .failureRateThreshold(70.0f)
                        .slowCallDurationThreshold(Duration.ofSeconds(5))
                        .build());

        // Register event listeners for ALL circuit breakers
        // State change events trigger logging and alerting
        registry.getEventPublisher()
                .onEntryAdded(event -> {
                    CircuitBreaker cb = event.getAddedEntry();
                    registerStateTransitionListener(cb);
                });

        return registry;
    }

    /**
     * Bind Resilience4j circuit breaker metrics to Micrometer.
     * This creates:
     *   resilience4j.circuitbreaker.state{name}
     *   resilience4j.circuitbreaker.calls{name, kind}
     *   resilience4j.circuitbreaker.failure.rate{name}
     *   resilience4j.circuitbreaker.slow.call.rate{name}
     * All visible in Prometheus + Grafana dashboard.
     */
    @Bean
    public TaggedCircuitBreakerMetrics circuitBreakerMetrics(
            CircuitBreakerRegistry registry,
            MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics metrics =
                TaggedCircuitBreakerMetrics
                        .ofCircuitBreakerRegistry(registry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }

    /**
     * Register state-transition event listener on a circuit breaker.
     * Logs WARN on every OPEN/HALF_OPEN transition for ops team visibility.
     */
    private void registerStateTransitionListener(CircuitBreaker cb) {
        cb.getEventPublisher()
                .onStateTransition(event -> {
                    CircuitBreaker.State from = event.getStateTransition()
                            .getFromState();
                    CircuitBreaker.State to = event.getStateTransition()
                            .getToState();

                    switch (to) {
                        case OPEN -> log.warn(
                                "Circuit breaker OPENED: service={} " +
                                        "transition={}->{} failureRate={}%",
                                cb.getName(), from, to,
                                String.format("%.1f",
                                        cb.getMetrics().getFailureRate()));

                        case HALF_OPEN -> log.info(
                                "Circuit breaker HALF_OPEN: service={} " +
                                        "testing with {} calls",
                                cb.getName(),
                                cb.getCircuitBreakerConfig()
                                        .getPermittedNumberOfCallsInHalfOpenState());

                        case CLOSED -> log.info(
                                "Circuit breaker CLOSED (recovered): service={}",
                                cb.getName());

                        default -> log.debug(
                                "Circuit breaker state change: service={} {}->{} ",
                                cb.getName(), from, to);
                    }
                })
                .onSlowCallRateExceeded(event ->
                        log.warn("Circuit breaker slow call threshold exceeded: " +
                                        "service={} slowCallRate={}%",
                                cb.getName(),
                                String.format("%.1f", event.getSlowCallRate())))
                .onFailureRateExceeded(event ->
                        log.warn("Circuit breaker failure rate threshold exceeded: " +
                                        "service={} failureRate={}%",
                                cb.getName(),
                                String.format("%.1f", event.getFailureRate())));
    }
}