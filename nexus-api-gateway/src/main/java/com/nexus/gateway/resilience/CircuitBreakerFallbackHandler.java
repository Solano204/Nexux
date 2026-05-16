package com.nexus.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Circuit Breaker Fallback Handler — Event listener and state manager.
 *
 * Responsibilities:
 *   1. Register event listeners on ALL circuit breakers at startup
 *   2. Emit Micrometer counters on circuit breaker events
 *   3. Log structured state transition events for ops team
 *   4. Provide helper methods for checking circuit breaker state
 *      (used by FallbackController to enrich fallback responses)
 *
 * Circuit Breaker event types:
 *   SUCCESS         — call succeeded
 *   ERROR           — call failed with recorded exception
 *   IGNORED_ERROR   — call failed with ignored exception (not counted)
 *   SLOW_CALL       — call succeeded but was slow
 *   NOT_PERMITTED   — call rejected (circuit OPEN)
 *   STATE_TRANSITION — circuit changed state
 *
 * This class listens to STATE_TRANSITION and NOT_PERMITTED events.
 * NOT_PERMITTED events mean the circuit is OPEN and requests are
 * being fast-failed — the fallback controller handles the response.
 *
 * Financial impact tracking:
 * - transaction-service OPEN → log estimated impact per minute
 * - account-service OPEN → log user-facing impact
 *
 * Architecture: this class OBSERVES circuit breakers; it does not
 * control them. The Resilience4j framework controls state transitions.
 */
@Slf4j
@Component
public class CircuitBreakerFallbackHandler {

    // Services where OPEN state has direct financial transaction impact
    private static final List<String> FINANCIAL_CRITICAL_SERVICES = List.of(
            "transaction-service",
            "account-service",
            "identity-service",
            "saga-orchestrator"
    );

    // Default wait duration if config is unavailable (30 seconds)
    private static final Duration DEFAULT_WAIT_DURATION = Duration.ofSeconds(30L);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry meterRegistry;

    // Counters for circuit breaker NOT_PERMITTED events (fast-fails)
    // These are the events that hit the fallback controller
    private Counter transactionServiceFastFails;
    private Counter accountServiceFastFails;
    private Counter totalFastFails;

    public CircuitBreakerFallbackHandler(
            CircuitBreakerRegistry circuitBreakerRegistry,
            MeterRegistry meterRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerListeners() {
        // Build counters
        transactionServiceFastFails = Counter.builder(
                        "gateway.circuit.breaker.fast.fails")
                .tag("service", "transaction-service")
                .description("Requests fast-failed by open circuit breaker")
                .register(meterRegistry);

        accountServiceFastFails = Counter.builder(
                        "gateway.circuit.breaker.fast.fails")
                .tag("service", "account-service")
                .register(meterRegistry);

        totalFastFails = Counter.builder(
                        "gateway.circuit.breaker.fast.fails")
                .tag("service", "all")
                .register(meterRegistry);

        // Register listeners on all existing circuit breakers
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(this::attachListeners);

        // Also listen for newly created circuit breakers
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event ->
                        attachListeners(event.getAddedEntry()));

        log.info("Circuit breaker event listeners registered for {} CBs",
                circuitBreakerRegistry.getAllCircuitBreakers().size());
    }

    /**
     * Check if a specific service's circuit breaker is currently OPEN.
     * Used by FallbackController to enrich fallback responses.
     */
    public boolean isCircuitOpen(String serviceName) {
        return circuitBreakerRegistry.find(serviceName)
                .map(cb -> cb.getState() == CircuitBreaker.State.OPEN
                        || cb.getState() == CircuitBreaker.State.HALF_OPEN)
                .orElse(false);
    }

    /**
     * Get current failure rate for a service's circuit breaker.
     * Returns -1 if circuit breaker not found.
     */
    public float getFailureRate(String serviceName) {
        return circuitBreakerRegistry.find(serviceName)
                .map(cb -> cb.getMetrics().getFailureRate())
                .orElse(-1.0f);
    }

    /**
     * Get seconds remaining until OPEN circuit transitions to HALF_OPEN.
     * Useful for enriching fallback responses with "retry after X seconds".
     */
    public long getEstimatedRecoverySeconds(String serviceName) {
        return circuitBreakerRegistry.find(serviceName)
                .map(this::extractWaitDuration)
                .orElse(DEFAULT_WAIT_DURATION)
                .toSeconds();
    }

    /**
     * Alternative: Get the circuit breaker state directly.
     * Use this if you only need to know if circuit is OPEN/HALF_OPEN/CLOSED.
     */
    public CircuitBreaker.State getCircuitState(String serviceName) {
        return circuitBreakerRegistry.find(serviceName)
                .map(CircuitBreaker::getState)
                .orElse(CircuitBreaker.State.CLOSED);
    }

    // ── Private helpers ───────────────────────────────────────

    /**
     * Extract wait duration from circuit breaker config.
     * Uses reflection for version compatibility since CircuitBreakerConfig
     * doesn't expose waitDurationInOpenState consistently across versions.
     */
    private Duration extractWaitDuration(CircuitBreaker cb) {
        try {
            // Try to access via reflection (works across versions)
            var configClass = cb.getCircuitBreakerConfig().getClass();
            var method = configClass.getMethod("getWaitDurationInOpenState");
            Object result = method.invoke(cb.getCircuitBreakerConfig());
            if (result instanceof Duration) {
                return (Duration) result;
            }
        } catch (Exception e) {
            log.debug("Unable to extract wait duration via reflection: {}", e.getMessage());
        }

        // Fallback: use configured default or return standard default
        return DEFAULT_WAIT_DURATION;
    }

    private void attachListeners(CircuitBreaker cb) {
        String name = cb.getName();

        // State transition events
        cb.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State from = event.getStateTransition()
                    .getFromState();
            CircuitBreaker.State to = event.getStateTransition()
                    .getToState();

            if (to == CircuitBreaker.State.OPEN) {
                handleCircuitOpened(name, cb);
            } else if (to == CircuitBreaker.State.CLOSED) {
                log.info(
                        "circuit.breaker.recovered service={} " +
                                "from={} to={}",
                        name, from, to);
            }

            // Emit state metric
            Counter.builder("gateway.circuit.breaker.transitions")
                    .tag("service", name)
                    .tag("from", from.name())
                    .tag("to", to.name())
                    .register(meterRegistry)
                    .increment();
        });

        // NOT_PERMITTED: circuit is open, requests are being rejected
        cb.getEventPublisher().onCallNotPermitted(event -> {
            totalFastFails.increment();

            if ("transaction-service".equals(name)) {
                transactionServiceFastFails.increment();
            } else if ("account-service".equals(name)) {
                accountServiceFastFails.increment();
            }

            log.debug("circuit.breaker.fast.fail service={}", name);
        });

        // Slow call events (circuit approaching threshold)
        cb.getEventPublisher().onSlowCallRateExceeded(event ->
                log.warn(
                        "circuit.breaker.slow.call.threshold service={} " +
                                "slowRate={}% threshold={}%",
                        name,
                        String.format("%.1f", event.getSlowCallRate()),
                        String.format("%.1f",
                                cb.getCircuitBreakerConfig()
                                        .getSlowCallRateThreshold())));
    }

    private void handleCircuitOpened(String serviceName,
                                     CircuitBreaker cb) {
        float failureRate = cb.getMetrics().getFailureRate();
        long waitSeconds = extractWaitDuration(cb).toSeconds();
        boolean isFinancialCritical =
                FINANCIAL_CRITICAL_SERVICES.contains(serviceName);

        if (isFinancialCritical) {
            log.warn(
                    "CIRCUIT_BREAKER_OPENED service={} " +
                            "failureRate={}% waitSeconds={} " +
                            "financialImpact=HIGH " +
                            "action=FALLBACK_RESPONSES_ACTIVE",
                    serviceName,
                    String.format("%.1f", failureRate),
                    waitSeconds);
        } else {
            log.warn(
                    "CIRCUIT_BREAKER_OPENED service={} " +
                            "failureRate={}% waitSeconds={}",
                    serviceName,
                    String.format("%.1f", failureRate),
                    waitSeconds);
        }
    }
}