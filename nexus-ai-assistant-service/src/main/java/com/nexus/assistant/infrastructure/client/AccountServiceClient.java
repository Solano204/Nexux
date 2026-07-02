package com.nexus.assistant.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    public AccountServiceClient(
            @Value("${nexus.services.account.url:http://nexus-account-service:8085}")
            String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Service", "nexus-ai-assistant-service")
                .build();
    }

    public String getBalance(String accountId, String userId) {
        try {
            return restClient.get()
                    .uri("/internal/v1/accounts/{accountId}/balance", accountId)
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("Balance fetch failed: accountId={}", accountId);
            return "{\"error\": \"BALANCE_UNAVAILABLE\", " +
                    "\"message\": \"Account balance temporarily unavailable.\"}";
        }
    }

    public String getAllBalances(String userId) {
        try {
            return restClient.get()
                    .uri("/internal/v1/accounts/balances")
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("All balances fetch failed: userId={}", userId);
            return "{\"error\": \"BALANCES_UNAVAILABLE\", " +
                    "\"message\": \"Account balances temporarily unavailable.\"}";
        }
    }

    public boolean isOwner(String accountId, String userId) {
        try {
            String response = restClient.get()
                    .uri("/internal/v1/accounts/{accountId}/owner", accountId)
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(String.class);
            return response != null && response.contains("true");
        } catch (Exception e) {
            log.warn("Ownership check failed: accountId={}", accountId);
            return false;
        }
    }
}