package com.nexus.gateway.resilience;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Fallback Controller — Handles circuit breaker fallback responses.
 *
 * Each downstream service has a specific fallback response tailored
 * to what the client should understand and do next.
 *
 * Pattern: Circuit Breaker Pattern + Fail Fast
 * - Returns 503 immediately instead of waiting for timeout
 * - Message tells user specifically what happened and what to do
 * - Financial-safe messages: always mention account was NOT charged
 */
@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    /**
     * Transaction Service fallback.
     * Critical: explicitly state "account has not been charged"
     * Users panic if they don't know if money was taken.
     */
    @PostMapping("/transaction")
    @GetMapping("/transaction")
    public Mono<ResponseEntity<Map<String, Object>>> transactionFallback(
            ServerWebExchange exchange) {

        log.warn("Transaction service circuit open — returning fallback");

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "TRANSACTION_SERVICE_UNAVAILABLE",
                        "message", "Transaction service temporarily unavailable. " +
                                "Your account has NOT been charged. " +
                                "Please try again in 30 seconds.",
                        "retryAfter", 30,
                        "timestamp", Instant.now().toString()
                )));
    }

    /**
     * Account Service fallback.
     */
    @GetMapping("/account")
    @PostMapping("/account")
    public Mono<ResponseEntity<Map<String, Object>>> accountFallback(
            ServerWebExchange exchange) {

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "ACCOUNT_SERVICE_UNAVAILABLE",
                        "message", "Account service temporarily unavailable. " +
                                "Your account data is safe. Please retry shortly.",
                        "retryAfter", 15,
                        "timestamp", Instant.now().toString()
                )));
    }

    /**
     * AI Assistant fallback — graceful degradation.
     * AI is non-critical — users can still use standard banking.
     */
    @GetMapping("/ai-assistant")
    @PostMapping("/ai-assistant")
    public Mono<ResponseEntity<Map<String, Object>>> aiAssistantFallback(
            ServerWebExchange exchange) {

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "AI_ASSISTANT_UNAVAILABLE",
                        "message", "AI assistant is temporarily unavailable. " +
                                "You can still use all standard banking features " +
                                "at /api/v1/",
                        "alternativeEndpoints", Map.of(
                                "accounts", "/api/v1/accounts",
                                "transactions", "/api/v1/transactions",
                                "ledger", "/api/v1/ledger"
                        ),
                        "timestamp", Instant.now().toString()
                )));
    }

    /**
     * Identity Service fallback.
     */
    @GetMapping("/identity")
    @PostMapping("/identity")
    public Mono<ResponseEntity<Map<String, Object>>> identityFallback(
            ServerWebExchange exchange) {

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "IDENTITY_SERVICE_UNAVAILABLE",
                        "message", "Authentication service temporarily unavailable. " +
                                "Existing sessions remain valid. Please retry login shortly.",
                        "timestamp", Instant.now().toString()
                )));
    }

    /**
     * Ledger Service fallback.
     */
    @GetMapping("/ledger")
    @PostMapping("/ledger")
    public Mono<ResponseEntity<Map<String, Object>>> ledgerFallback(
            ServerWebExchange exchange) {

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "LEDGER_SERVICE_UNAVAILABLE",
                        "message", "Financial records temporarily unavailable. " +
                                "No transactions have been affected.",
                        "timestamp", Instant.now().toString()
                )));
    }

    /**
     * Analytics Service fallback.
     */
    @GetMapping("/analytics")
    @PostMapping("/analytics")
    public Mono<ResponseEntity<Map<String, Object>>> analyticsFallback(
            ServerWebExchange exchange) {

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "ANALYTICS_SERVICE_UNAVAILABLE",
                        "message", "Analytics data temporarily unavailable. " +
                                "Please check back shortly.",
                        "timestamp", Instant.now().toString()
                )));
    }

    /**
     * Fraud Service fallback.
     */
    @GetMapping("/fraud")
    @PostMapping("/fraud")
    public Mono<ResponseEntity<Map<String, Object>>> fraudFallback(
            ServerWebExchange exchange) {

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "FRAUD_SERVICE_UNAVAILABLE",
                        "message", "Security verification service temporarily unavailable.",
                        "timestamp", Instant.now().toString()
                )));
    }

    /**
     * Webhook fallback.
     */
    @PostMapping("/webhook")
    public Mono<ResponseEntity<Map<String, Object>>> webhookFallback(
            ServerWebExchange exchange) {

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "WEBHOOK_HANDLER_UNAVAILABLE",
                        "message", "Webhook processing temporarily unavailable.",
                        "timestamp", Instant.now().toString()
                )));
    }

    /**
     * Generic fallback for any unspecified service.
     */
    @GetMapping("/generic")
    @PostMapping("/generic")
    public Mono<ResponseEntity<Map<String, Object>>> genericFallback(
            ServerWebExchange exchange) {

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", "SERVICE_TEMPORARILY_UNAVAILABLE",
                        "message", "This service is temporarily unavailable. " +
                                "Please try again shortly.",
                        "timestamp", Instant.now().toString()
                )));
    }
}