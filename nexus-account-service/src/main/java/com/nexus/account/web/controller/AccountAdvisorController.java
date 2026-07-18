package com.nexus.account.web.controller;

import com.nexus.account.application.query.AccountQueryService;
import com.nexus.account.domain.exception.UnauthorizedException;
import com.nexus.account.infrastructure.ai.AccountAdvisorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(
        name = "Account Advisor",
        description = "AI financial advisor for a specific account — conversational chat and " +
                "proactive weekly insights, backed by Spring AI + pgvector transaction-history RAG."
)
public class AccountAdvisorController {

    private final AccountAdvisorService advisorService;
    private final AccountQueryService queryService;

    @Operation(
            summary = "Chat with the AI financial advisor (streaming)",
            description = "Server-Sent Events stream — tokens arrive as the model generates them, not " +
                    "as one blocking response. The advisor's context is enriched with this account's " +
                    "current analytics summary and a memory-advisor-backed conversation history keyed " +
                    "by sessionId (auto-generated if omitted). Consume with an SSE client, not a plain " +
                    "HTTP client expecting a single JSON body."
    )
    @ApiResponse(responseCode = "200", description = "text/event-stream of advisor response chunks")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Account exists but does not belong to the caller",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(
            value = "/{accountId}/advisor/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter chat(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId,
            @RequestBody AdvisorChatRequest request,
            HttpServletRequest httpRequest) {

        UUID userId = extractUserId(httpRequest);
        queryService.verifyOwnership(accountId, userId);

        String sessionId = request.sessionId() != null
                ? request.sessionId()
                : "advisor-" + accountId + "-" + userId;

        SseEmitter emitter = new SseEmitter(120_000L);

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        advisorService.getAdvisorResponseStream(accountId, userId, request.message(), sessionId)
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );

        return emitter;
    }

    @Operation(
            summary = "Get proactive financial insights",
            description = "Non-streaming, single-shot advice generated from the account's current " +
                    "analytics summary — the same advice weekly-insight job stores under " +
                    "savingsOpportunities in MongoDB. Slower than the chat endpoint (waits for the " +
                    "full model response before returning) since there's no incremental UI to stream to."
    )
    @ApiResponse(responseCode = "200", description = "Advice generated")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Account exists but does not belong to the caller",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{accountId}/advisor/insights")
    public AccountAdvisorService.FinancialAdviceResponse getInsights(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId,
            HttpServletRequest httpRequest) {
        UUID userId = extractUserId(httpRequest);
        queryService.verifyOwnership(accountId, userId);
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
