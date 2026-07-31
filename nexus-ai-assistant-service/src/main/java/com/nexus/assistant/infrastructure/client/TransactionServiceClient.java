package com.nexus.assistant.infrastructure.client;

import com.nexus.tracing.http.InternalServiceHeaderInterceptor;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Transaction Service Client — calls nexus-transaction-service.
 *
 * Used by: get_transaction_history (ES search), transfer_funds (initiate).
 */
@Slf4j
@Component
public class TransactionServiceClient {

    private final RestClient restClient;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final ThreadPoolBulkhead bulkhead;

    public TransactionServiceClient(
            @Value("${nexus.services.transaction.url:http://nexus-transaction-service:8086}")
            String baseUrl,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ThreadPoolBulkheadRegistry bulkheadRegistry) {
        // Timeouts (resilience guide, Fase 1) — see AccountServiceClient.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(5000);

        // InternalServiceHeaderInterceptor (Fase 3, see
        // CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md)
        // replaces the old bare X-Internal-Service defaultHeader and also
        // auto-forwards X-User-Id.
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(new InternalServiceHeaderInterceptor("nexus-ai-assistant-service"))
                .build();

        // Programmatic retry + circuit breaker (resiliencia guide, Fases 2-3)
        // — see AccountServiceClient. Used only by searchTransactions (GET,
        // read-only, safe) — NOT by initiateTransfer: that POST doesn't send
        // an idempotencyKey to /api/v1/transactions/transfer, so retrying it
        // could create a duplicate real transfer. Fix that gap first
        // (Saga/idempotencia guide territory, not this one) before this call
        // is retry-safe.
        // Retry-After-aware (Fase 6) — see RetryAfterSupport.
        this.retry = RetryAfterSupport.buildRetry("transaction-service-reads", 2);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("ai-assistant-reads");
        // Bulkhead (Fase 4) is a different concern from retry-safety — it
        // just bounds concurrent in-flight calls to this dependency, so it
        // applies to BOTH methods below, sharing one capacity budget for
        // "calls to transaction-service" regardless of read/write.
        this.bulkhead = bulkheadRegistry.bulkhead("transaction-service-calls");
    }

    /** See AccountServiceClient for the stack-order reasoning. */
    private <T> T resilient(Supplier<T> call) {
        Supplier<T> protectedCall = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, call));
        return runOnBulkhead(protectedCall);
    }

    /** Bulkhead-only, no retry/circuit breaker — see initiateTransfer. */
    private <T> T bulkheadOnly(Supplier<T> call) {
        return runOnBulkhead(call);
    }

    private <T> T runOnBulkhead(Supplier<T> call) {
        try {
            // ThreadPoolBulkhead.executeSupplier() returns CompletionStage,
            // not CompletableFuture - .get() isn't declared there directly.
            return bulkhead.executeSupplier(call).toCompletableFuture().get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public String searchTransactions(String userId, String accountId,
                                     String startDate, String endDate,
                                     String merchantName, int limit) {
        try {
            StringBuilder uri = new StringBuilder(
                    "/internal/v1/transactions/search?accountId=" + accountId +
                            "&limit=" + limit);
            if (startDate != null) uri.append("&startDate=").append(startDate);
            if (endDate != null) uri.append("&endDate=").append(endDate);
            if (merchantName != null) uri.append("&merchantName=").append(merchantName);

            String path = uri.toString();
            return resilient(() -> restClient.get()
                    .uri(path)
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(String.class));
        } catch (Exception e) {
            log.warn("Transaction search failed: {}", e.getMessage());
            return "{\"error\": \"SEARCH_UNAVAILABLE\", " +
                    "\"message\": \"Transaction search temporarily unavailable.\"}";
        }
    }

    public String initiateTransfer(String userId, String sourceAccountId,
                                   String targetAccountId, String amount,
                                   String currency, String description) {
        // Bulkhead only — no retry/circuit breaker, see constructor comment.
        // This POST has no idempotencyKey, so a retried attempt could
        // double-execute a real money transfer.
        try {
            Map<String, String> body = Map.of(
                    "sourceAccountId", sourceAccountId,
                    "targetAccountId", targetAccountId,
                    "amount", amount,
                    "currency", currency != null ? currency : "MXN",
                    "description", description != null ? description : "AI Assistant transfer"
            );

            return bulkheadOnly(() -> restClient.post()
                    .uri("/internal/v1/transactions/transfer")
                    .header("X-User-Id", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
        } catch (Exception e) {
            log.error("Transfer initiation failed: {}", e.getMessage());
            return "{\"error\": \"TRANSFER_FAILED\", " +
                    "\"message\": \"Transfer could not be initiated.\", " +
                    "\"note\": \"No funds have been moved.\"}";
        }
    }
}
