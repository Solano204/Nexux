package com.nexus.assistant.infrastructure.client;

import com.nexus.tracing.http.InternalServiceHeaderInterceptor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Fraud Service Client — calls nexus-fraud-service internal endpoints.
 *
 * SECURITY: Only returns user-facing summaries. Never exposes
 * raw risk scores, triggering factors, or model internals.
 */
@Slf4j
@Component
public class FraudServiceClient {

    private final RestClient restClient;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final ThreadPoolBulkhead bulkhead;

    public FraudServiceClient(
            @Value("${nexus.services.fraud.url:http://nexus-fraud-service:8087}")
            String baseUrl,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ThreadPoolBulkheadRegistry bulkheadRegistry) {
        // Timeouts (resilience guide, Fase 1) — see AccountServiceClient.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(5000);

        // InternalServiceHeaderInterceptor (Fase 3, see
        // CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md)
        // replaces the old bare X-Internal-Service defaultHeader and adds
        // X-User-Id automatically - getRecentAlertSummaries(userId, ...)
        // took userId as a param but never forwarded it as the header the
        // way AccountServiceClient/TransactionServiceClient already did;
        // this closes that gap for free instead of hand-adding
        // .header("X-User-Id", userId) at each call site.
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(new InternalServiceHeaderInterceptor("nexus-ai-assistant-service"))
                .build();

        // Retry-After-aware (Fase 6) — see RetryAfterSupport. Circuit
        // breaker + bulkhead (Fases 3-4) — see AccountServiceClient.
        this.retry = RetryAfterSupport.buildRetry("fraud-service-reads", 2);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("ai-assistant-reads");
        this.bulkhead = bulkheadRegistry.bulkhead("fraud-service-calls");
    }

    private <T> T resilient(Supplier<T> call) {
        Supplier<T> protectedCall = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, call));
        try {
            // ThreadPoolBulkhead.executeSupplier() returns CompletionStage,
            // not CompletableFuture - .get() isn't declared there directly.
            return bulkhead.executeSupplier(protectedCall).toCompletableFuture().get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public String getRecentAlertSummaries(String userId, int daysBack) {
        try {
            return resilient(() -> restClient.get()
                    .uri("/internal/v1/fraud/alerts/user/{userId}?daysBack={days}",
                            userId, daysBack)
                    .retrieve()
                    .body(String.class));
        } catch (Exception e) {
            log.warn("Fraud alerts fetch failed: userId={}", userId);
            return "{\"error\": \"ALERTS_UNAVAILABLE\", " +
                    "\"message\": \"Security alerts temporarily unavailable.\"}";
        }
    }
}
