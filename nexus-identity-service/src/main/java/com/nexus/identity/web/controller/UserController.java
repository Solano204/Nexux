package com.nexus.identity.web.controller;

import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.application.query.UserQueryService;
import com.nexus.identity.web.dto.request.*;
import com.nexus.identity.web.dto.response.*;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * User Controller — Authenticated user profile endpoints.
 *
 * GET    /api/v1/users/me          — Get profile (CQRS read)
 * PUT    /api/v1/users/me          — Update profile (CQRS write)
 * POST   /api/v1/users/me/change-password
 * GET    /api/v1/users/me/sessions — List active sessions
 * DELETE /api/v1/users/me/sessions/{sessionId} — Terminate session
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserCommandService commandService;
    private final UserQueryService queryService;
    private final Tracer tracer;

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getMyProfile(
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        return ResponseEntity.ok(queryService.getUserProfile(userId));
    }

    @GetMapping("/me/sessions")
    public ResponseEntity<List<SessionSummaryResponse>> getMySessions(
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        return ResponseEntity.ok(queryService.getActiveSessions(userId));
    }

    @DeleteMapping("/me/sessions/{sessionId}")
    public ResponseEntity<Void> terminateSession(
            @PathVariable UUID sessionId,
            HttpServletRequest request) {

        UUID userId = extractUserId(request);
        commandService.terminateSession(userId, sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/change-password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest req,
            HttpServletRequest request) {

        UUID userId = extractUserId(request);
        String traceId = getTraceId();

        commandService.changePassword(
                userId, req,
                extractCurrentSessionId(request),
                getClientIp(request),
                traceId
        );

        return ResponseEntity.ok().build();
    }

    private UUID extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return UUID.fromString(userId);
    }

    private UUID extractCurrentSessionId(HttpServletRequest request) {
        // Extract from JWT — decoded and stored as attribute by security filter
        Object sessionId = request.getAttribute("currentSessionId");
        return sessionId != null ? (UUID) sessionId : null;
    }

    private String getTraceId() {
        return tracer.currentSpan() != null
                ? tracer.currentSpan().context().traceId()
                : "no-trace";
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
    }
}