package com.nexus.account.web.controller;

import com.nexus.account.domain.exception.UnauthorizedException;
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
 *   Returns: Server-Sent Events (SSE) streaming response
 *
 * GET /api/v1/accounts/{id}/advisor/insights
 *   Returns: Proactive weekly financial insight (structured JSON)
 *
 * Section 3: Streaming SSE response
 * Section 7: Hybrid memory (in-memory window + pgvector semantic)
 * Section 10: Advanced RAG over user's transaction history
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountAdvisorController {

    private final AccountAdvisorService advisorService;

    /**
     * Streaming AI advisor chat — SSE response.
     *
     * The advisor has access to:
     * - User's actual transaction history (pgvector RAG)
     * - Previous advice sessions (semantic memory)
     * - Current account analytics (MongoDB)
     * - Conversation context (in-memory window memory)
     */
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

    /**
     * Proactive financial insights — non-streaming structured response.
     * Returns AI-generated savings opportunities and action items.
     */
    @GetMapping("/{accountId}/advisor/insights")
    public AccountAdvisorService.FinancialAdviceResponse getInsights(
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