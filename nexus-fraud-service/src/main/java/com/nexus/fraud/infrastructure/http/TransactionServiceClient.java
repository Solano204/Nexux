package com.nexus.fraud.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Transaction Service HTTP client - the only synchronous cross-service
 * call in the platform (everything else goes through Kafka).
 *
 * @CircuitBreaker only takes effect when called through the Spring proxy
 * for this bean, so the HTTP calls live here rather than as private
 * methods on the callers (VelocityCheckTool/AccountRelationshipTool) -
 * a self-invoked private method silently bypasses the AOP proxy and the
 * annotation would never trigger. No fallbackMethod is declared: both
 * callers already catch any exception from these calls and substitute a
 * neutral default, so letting CallNotPermittedException propagate reuses
 * that existing handling instead of duplicating it here.
 *
 * @Retry (resiliencia guide, Fase 2): both methods are GET, read-only, no
 * business side effect — safe to retry. Retry wraps INSIDE CircuitBreaker
 * (Resilience4j applies annotations outside-in on the declaration, so
 * CircuitBreaker sees the fully-retried outcome) — a sustained outage
 * still trips the breaker after its failure-rate threshold, retry only
 * absorbs single transient blips.
 */
@Component
@RequiredArgsConstructor
public class TransactionServiceClient {

    private static final String BASE_URL = "http://nexus-transaction-service:8086";

    private final RestTemplate transactionServiceClient;

    @CircuitBreaker(name = "transaction-service")
    @Retry(name = "transaction-service-reads")
    public JsonNode getVelocity(String userId, int windowMinutes) {
        String url = BASE_URL + "/internal/v1/streams/velocity/" + userId
                + "?windowMinutes=" + windowMinutes;
        return transactionServiceClient.getForObject(url, JsonNode.class);
    }

    @CircuitBreaker(name = "transaction-service")
    @Retry(name = "transaction-service-reads")
    public JsonNode getAccountRelationship(String sourceAccountId, String targetAccountId) {
        String url = BASE_URL + "/internal/v1/accounts/relationship"
                + "?source=" + sourceAccountId + "&target=" + targetAccountId;
        return transactionServiceClient.getForObject(url, JsonNode.class);
    }
}
