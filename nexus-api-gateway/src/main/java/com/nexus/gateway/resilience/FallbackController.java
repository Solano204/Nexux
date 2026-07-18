package com.nexus.gateway.resilience;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@Tag(name = "Circuit Breaker Fallbacks", description = "Never called directly by a client — these are the responses Spring Cloud Gateway's circuit breaker returns when a downstream service is down, one per service, worded for what a user should know/do next.")
public class FallbackController {

    @Operation(summary = "Transaction service fallback", description = "Explicitly states the account was NOT charged — the one fallback where ambiguity would cause real user panic.")
    @ApiResponse(responseCode = "503", description = "Transaction service unavailable")
    @RequestMapping("/transaction")
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

    @Operation(summary = "Account service fallback")
    @ApiResponse(responseCode = "503", description = "Account service unavailable")
    @RequestMapping("/account")
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

    @Operation(summary = "AI assistant fallback", description = "Graceful degradation — AI is non-critical, response points the client back to standard banking endpoints that still work.")
    @ApiResponse(responseCode = "503", description = "AI assistant unavailable")
    @RequestMapping("/ai-assistant")
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

    @Operation(summary = "Identity service fallback", description = "States existing sessions remain valid — only new logins are affected.")
    @ApiResponse(responseCode = "503", description = "Identity service unavailable")
    @RequestMapping("/identity")
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

    @Operation(summary = "Ledger service fallback")
    @ApiResponse(responseCode = "503", description = "Ledger service unavailable")
    @RequestMapping("/ledger")
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

    @Operation(summary = "Analytics service fallback")
    @ApiResponse(responseCode = "503", description = "Analytics service unavailable")
    @RequestMapping("/analytics")
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

    @Operation(summary = "Fraud service fallback")
    @ApiResponse(responseCode = "503", description = "Fraud service unavailable")
    @RequestMapping("/fraud")
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

    @Operation(summary = "Webhook processing fallback")
    @ApiResponse(responseCode = "503", description = "Webhook handler unavailable")
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

    @Operation(summary = "Generic fallback", description = "Catch-all for any service without a dedicated fallback route.")
    @ApiResponse(responseCode = "503", description = "Service unavailable")
    @RequestMapping("/generic")
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