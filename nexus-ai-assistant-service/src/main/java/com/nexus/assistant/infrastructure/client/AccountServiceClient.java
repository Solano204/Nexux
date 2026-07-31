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
 * Account Service Client — calls nexus-account-service internal endpoints.
 *
 * Used by: get_account_balance, get_account_info, transfer_funds (ownership).
 * Eureka: lb://nexus-account-service. Circuit breaker on all calls.
 */
@Slf4j
@Component
public class AccountServiceClient {

    private final RestClient restClient;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final ThreadPoolBulkhead bulkhead;

    public AccountServiceClient(
            @Value("${nexus.services.account.url:http://nexus-account-service:8085}")
            String baseUrl,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ThreadPoolBulkheadRegistry bulkheadRegistry) {
        // Timeouts (resilience guide, Fase 1): each tool-call must stay
        // small relative to the overall chat turn's ~45s budget (gateway's
        // ai-assistant-service route) — a single turn can invoke several
        // tools plus multiple LLM round trips.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(5000);

        // InternalServiceHeaderInterceptor (Fase 3, see
        // CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md)
        // replaces the old bare X-Internal-Service defaultHeader and also
        // auto-forwards X-User-Id - the per-call .header("X-User-Id", ...)
        // below is now redundant but left in place (harmless, same value)
        // rather than touched, since every method here already passes
        // userId explicitly and this wasn't broken.
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(new InternalServiceHeaderInterceptor("nexus-ai-assistant-service"))
                .build();

        // Programmatic retry + circuit breaker (resiliencia guide, Fases 2-3),
        // not annotations: every method here already wraps the call in
        // try/catch to return a graceful fallback string instead of
        // throwing, so an AOP-based @Retry/@CircuitBreaker would never see
        // a failure to react to — this method always returns normally
        // (fallback JSON), even when the underlying call fails.
        // Retry-After-aware (Fase 6) — see RetryAfterSupport.
        this.retry = RetryAfterSupport.buildRetry("account-service-reads", 2);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("ai-assistant-reads");
        // Bulkhead (Fase 4), isolated per dependency — see application.yml.
        this.bulkhead = bulkheadRegistry.bulkhead("account-service-calls");
    }

    /**
     * Stack order matches the guide's model (Timeout -> Retry ->
     * CircuitBreaker -> Bulkhead): Bulkhead is outermost, gating entry
     * into the whole protected operation before any retry/breaker logic
     * runs; CircuitBreaker wraps Retry so the breaker sees one pass/fail
     * outcome per logical call (after retries are exhausted), not one per
     * raw HTTP attempt — the latter would make its failure-rate window
     * noisy and trip 2-3x faster than intended.
     */
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

    public String getBalance(String accountId, String userId) {
        try {
            return resilient(() -> restClient.get()
                    .uri("/internal/v1/accounts/{accountId}/balance", accountId)
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(String.class));
        } catch (Exception e) {
            log.warn("Balance fetch failed: accountId={}", accountId);
            return "{\"error\": \"BALANCE_UNAVAILABLE\", " +
                    "\"message\": \"Account balance temporarily unavailable.\"}";
        }
    }

    public String getAllBalances(String userId) {
        try {
            return resilient(() -> restClient.get()
                    .uri("/internal/v1/accounts/balances")
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(String.class));
        } catch (Exception e) {
            log.warn("All balances fetch failed: userId={}", userId);
            return "{\"error\": \"BALANCES_UNAVAILABLE\", " +
                    "\"message\": \"Account balances temporarily unavailable.\"}";
        }
    }

    public boolean isOwner(String accountId, String userId) {
        try {
            String response = resilient(() -> restClient.get()
                    .uri("/internal/v1/accounts/{accountId}/owner", accountId)
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(String.class));
            return response != null && response.contains("true");
        } catch (Exception e) {
            log.warn("Ownership check failed: accountId={}", accountId);
            return false;
        }
    }
}
