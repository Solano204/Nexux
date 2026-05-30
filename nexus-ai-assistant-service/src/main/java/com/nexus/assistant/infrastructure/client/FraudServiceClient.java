package com.nexus.assistant.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    public FraudServiceClient(
            @Value("${nexus.services.fraud.url:http://nexus-fraud-service:8087}")
            String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Service", "nexus-ai-assistant-service")
                .build();
    }

    public String getRecentAlertSummaries(String userId, int daysBack) {
        try {
            return restClient.get()
                    .uri("/internal/v1/fraud/alerts/user/{userId}?daysBack={days}",
                            userId, daysBack)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("Fraud alerts fetch failed: userId={}", userId);
            return "{\"error\": \"ALERTS_UNAVAILABLE\", " +
                    "\"message\": \"Security alerts temporarily unavailable.\"}";
        }
    }
}