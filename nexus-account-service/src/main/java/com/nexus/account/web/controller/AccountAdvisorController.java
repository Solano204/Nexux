package com.nexus.account.web.controller;

import com.nexus.account.infrastructure.ai.AccountAdvisorService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Account Advisor Controller — AI-powered financial advice.
 *
 * POST /api/v1/accounts/{id}/advisor/chat
 * Returns: Server-Sent Events (SSE) streaming response
 *
 * Section 3: Streaming SSE response
 * Section 7: Hybrid memory (JDBC window + pgvector semantic)
 * Section 10: Advanced RAG over user's transaction history
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountAdvisorController {

    private final AccountAdvisorService advisorService;

    @PostMapping(
            value = "/{accountId}/advisor/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> chat(
            @PathVariable UUID accountId,
            @RequestBody AdvisorChatRequest request,
            HttpServletRequest httpRequest) {

        UUID userId = extractUserId(httpRequest);

        String sessionId = request.sessionId() != null
                ? request.sessionId()
                : "advisor-" + accountId + "-" + userId;

        return advisorService.getAdvisorResponseStream(
                accountId, userId, request.message(), sessionId);
    }

    @GetMapping("/{accountId}/advisor/insights")
    public AdvisorService.FinancialAdviceResponse getInsights(
            @PathVariable UUID accountId) {
        return advisorService.getProactiveAdvice(accountId);
    }

    private UUID extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return UUID.fromString(userId);
    }

    public record AdvisorChatRequest(
            String message,
            String sessionId
    ) {}
}