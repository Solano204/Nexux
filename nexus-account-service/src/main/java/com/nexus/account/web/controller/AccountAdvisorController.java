package com.nexus.account.web.controller;

import com.nexus.account.application.query.AccountQueryService;
import com.nexus.account.domain.exception.UnauthorizedException;
import com.nexus.account.infrastructure.ai.AccountAdvisorService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountAdvisorController {

    private final AccountAdvisorService advisorService;
    private final AccountQueryService queryService;

    @PostMapping(
            value = "/{accountId}/advisor/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter chat(
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

    @GetMapping("/{accountId}/advisor/insights")
    public AccountAdvisorService.FinancialAdviceResponse getInsights(
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
