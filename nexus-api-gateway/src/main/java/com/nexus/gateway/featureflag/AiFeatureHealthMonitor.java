package com.nexus.gateway.featureflag;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI Feature Health Monitor (resiliencia guide, Fase 7, punto 3).
 *
 * Automatic activation rule tied to a real SLI signal (Fase 5), not a
 * dashboard someone has to be watching: listens to the gateway's own
 * "ai-assistant-service" CircuitBreaker (Fase 3, TIME_BASED — the same
 * one already tracking real failure rate for this dependency) and, if it
 * stays OPEN for a sustained window (not just one blip — see
 * SUSTAINED_OPEN_THRESHOLD), disables the ai-assistant feature flag
 * automatically.
 *
 * Deliberately asymmetric: auto-DISABLE is safe to automate (worst case,
 * a still-healthy feature gets turned off for a bit). Auto-RE-ENABLE is
 * NOT automated here — flapping a feature back on right after a breaker
 * closes risks re-exposing users to a dependency that's still recovering,
 * and for anything touching money-adjacent flows, a human confirming
 * "yes, actually healthy now" is worth the extra step. Re-enable via
 * FeatureFlagAdminController.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiFeatureHealthMonitor {

    private static final String FEATURE = "ai-assistant";
    private static final String CIRCUIT_BREAKER_NAME = "ai-assistant-service";
    private static final Duration SUSTAINED_OPEN_THRESHOLD = Duration.ofMinutes(2);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final FeatureFlagService featureFlagService;

    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    @PostConstruct
    public void subscribe() {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        breaker.getEventPublisher().onStateTransition(event -> {
            switch (event.getStateTransition().getToState()) {
                case OPEN -> {
                    openedAt.compareAndSet(null, Instant.now());
                    scheduleSustainedCheck();
                }
                case CLOSED, HALF_OPEN -> openedAt.set(null);
                default -> { /* no-op */ }
            }
        });

        log.info("AiFeatureHealthMonitor subscribed to circuit breaker '{}'",
                CIRCUIT_BREAKER_NAME);
    }

    private void scheduleSustainedCheck() {
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(SUSTAINED_OPEN_THRESHOLD.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            Instant since = openedAt.get();
            if (since == null) {
                return; // recovered before the sustained threshold — no action
            }

            CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
            if (breaker.getState() == CircuitBreaker.State.OPEN) {
                featureFlagService.disable(FEATURE,
                        "Circuit breaker '" + CIRCUIT_BREAKER_NAME +
                                "' has been OPEN continuously since " + since +
                                " (>" + SUSTAINED_OPEN_THRESHOLD.toMinutes() + "min)")
                        .subscribe();
            }
        });
    }
}
