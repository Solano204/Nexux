package com.nexus.assistant.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Transaction Service Client — calls nexus-transaction-service.
 *
 * Used by: get_transaction_history (ES search), transfer_funds (initiate).
 */
@Slf4j
@Component
public class TransactionServiceClient {

    private final RestClient restClient;

    public TransactionServiceClient(
            @Value("${nexus.services.transaction.url:http://nexus-transaction-service:8086}")
            String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Service", "nexus-ai-assistant-service")
                .build();
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

            return restClient.get()
                    .uri(uri.toString())
                    .header("X-User-Id", userId)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("Transaction search failed: {}", e.getMessage());
            return "{\"error\": \"SEARCH_UNAVAILABLE\", " +
                    "\"message\": \"Transaction search temporarily unavailable.\"}";
        }
    }

    public String initiateTransfer(String userId, String sourceAccountId,
                                   String targetAccountId, String amount,
                                   String currency, String description) {
        try {
            Map<String, String> body = Map.of(
                    "sourceAccountId", sourceAccountId,
                    "targetAccountId", targetAccountId,
                    "amount", amount,
                    "currency", currency != null ? currency : "MXN",
                    "description", description != null ? description : "AI Assistant transfer"
            );

            return restClient.post()
                    .uri("/internal/v1/transactions/transfer")
                    .header("X-User-Id", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("Transfer initiation failed: {}", e.getMessage());
            return "{\"error\": \"TRANSFER_FAILED\", " +
                    "\"message\": \"Transfer could not be initiated.\", " +
                    "\"note\": \"No funds have been moved.\"}";
        }
    }
}