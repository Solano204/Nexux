package com.nexus.account.web.controller;

import com.nexus.account.application.query.AccountQueryService;
import com.nexus.account.domain.exception.UnauthorizedException;
import com.nexus.account.web.dto.response.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Account Controller — User-facing account management endpoints.
 *
 * GET /api/v1/accounts              — List user's accounts
 * GET /api/v1/accounts/{id}         — Account details
 * GET /api/v1/accounts/{id}/balance — Balance (Redis-only, fast)
 * GET /api/v1/accounts/{id}/events  — Event history (paginated)
 * GET /api/v1/accounts/{id}/analytics — MongoDB analytics
 *
 * Authentication: X-User-Id header (set by API Gateway after JWT validation)
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountQueryService queryService;

    @GetMapping
    public ResponseEntity<List<AccountSummaryResponse>> getMyAccounts(
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        return ResponseEntity.ok(queryService.getUserAccounts(userId));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountDetailResponse> getAccountDetail(
            @PathVariable UUID accountId,
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        return ResponseEntity.ok(
                queryService.getAccountDetail(accountId, userId));
    }

    /**
     * Balance endpoint — reads from Redis cache ONLY.
     * If cache miss, returns 503 with Retry-After: 1
     * (cache miss triggers background refresh).
     */
    @GetMapping("/{accountId}/balance")
    public ResponseEntity<?> getBalance(
            @PathVariable UUID accountId) {

        var cached = queryService.getBalanceCached(accountId);

        if (cached == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "1")
                    .body(Map.of(
                            "error", "BALANCE_CACHE_WARMING",
                            "message", "Balance data is being refreshed. Retry in 1 second."
                    ));
        }

        return ResponseEntity.ok(cached);
    }

    @GetMapping("/{accountId}/events")
    public ResponseEntity<Page<AccountEventResponse>> getAccountEvents(
            @PathVariable UUID accountId,
            Pageable pageable) {
        return ResponseEntity.ok(
                queryService.getAccountEvents(accountId, pageable));
    }

    @GetMapping("/{accountId}/analytics")
    public ResponseEntity<?> getAccountAnalytics(
            @PathVariable UUID accountId) {
        var analytics = queryService.getAnalytics(accountId);
        if (analytics == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(analytics);
    }

    private UUID extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return UUID.fromString(userId);
    }
}