package com.nexus.account.web.controller;

import com.nexus.account.application.query.AccountQueryService;
import com.nexus.account.domain.exception.UnauthorizedException;
import com.nexus.account.web.dto.response.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
@Tag(
        name = "Accounts",
        description = "Balances, account details, and event history for the caller's own accounts. " +
                "Every endpoint verifies the account belongs to the X-User-Id caller — accountId is " +
                "an enumerable UUID, not a capability token."
)
public class AccountController {

    private final AccountQueryService queryService;

    @Operation(
            summary = "List the caller's accounts",
            description = "Returns every account owned by the authenticated user, newest first. " +
                    "This is the only account-listing endpoint — there is no cross-user listing."
    )
    @ApiResponse(responseCode = "200", description = "Accounts retrieved (empty array if the user has none)")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    public ResponseEntity<List<AccountSummaryResponse>> getMyAccounts(
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        return ResponseEntity.ok(queryService.getUserAccounts(userId));
    }

    @Operation(
            summary = "Get account detail",
            description = "Full account detail including daily/monthly transaction limits, minimum " +
                    "balance, and interest rate — not just the summary fields from the list endpoint."
    )
    @ApiResponse(responseCode = "200", description = "Account detail retrieved")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Account exists but does not belong to the caller",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No account with this ID",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountDetailResponse> getAccountDetail(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId,
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        return ResponseEntity.ok(
                queryService.getAccountDetail(accountId, userId));
    }

    @Operation(
            summary = "Get current balance",
            description = "Reads from the Redis balance cache only, never Postgres directly — this is " +
                    "the fast path for a UI's balance display. On a cache miss (rare — write-through, " +
                    "refreshed on every balance-changing event) this returns 503 immediately rather " +
                    "than falling back to a slower read, with Retry-After: 1 telling the client exactly " +
                    "how long the background refresh takes."
    )
    @ApiResponse(responseCode = "200", description = "Balance retrieved from cache",
            content = @Content(examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                    value = "{\"availableBalance\": 1250.75, \"reservedAmount\": 50.00, " +
                            "\"totalBalance\": 1300.75, \"currency\": \"MXN\", \"status\": \"ACTIVE\"}")))
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Account exists but does not belong to the caller",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Balance cache is warming — retry after the given delay",
            headers = @io.swagger.v3.oas.annotations.headers.Header(
                    name = "Retry-After", description = "Seconds to wait before retrying", schema = @Schema(type = "integer")),
            content = @Content(examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                    value = "{\"error\": \"BALANCE_CACHE_WARMING\", \"message\": \"Balance data is being refreshed. Retry in 1 second.\"}")))
    @GetMapping("/{accountId}/balance")
    public ResponseEntity<?> getBalance(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId,
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        var cached = queryService.getBalanceCached(accountId, userId);

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

    @Operation(
            summary = "Get account event history",
            description = "Paginated ledger of balance-affecting events for this account (reservations, " +
                    "releases, deposits, fees) — chronological audit trail, not the double-entry ledger " +
                    "itself (see nexus-ledger-service for that)."
    )
    @ApiResponse(responseCode = "200", description = "Events retrieved (empty page if none)")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Account exists but does not belong to the caller",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{accountId}/events")
    public ResponseEntity<Page<AccountEventResponse>> getAccountEvents(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId,
            @Parameter(description = "Standard Spring pagination — page, size, sort (e.g. sort=occurredAt,desc)")
            Pageable pageable,
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        return ResponseEntity.ok(
                queryService.getAccountEvents(accountId, userId, pageable));
    }

    @Operation(
            summary = "Get AI-generated account analytics",
            description = "Pre-aggregated spending/income analytics from MongoDB (built by " +
                    "nexus-analytics-service's Kafka Streams pipeline, not computed on request). " +
                    "Returns 404 if analytics haven't been computed yet for this account (new " +
                    "accounts, or accounts with no transaction history)."
    )
    @ApiResponse(responseCode = "200", description = "Analytics document retrieved")
    @ApiResponse(responseCode = "401", description = "X-User-Id header missing",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Account exists but does not belong to the caller",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No analytics computed yet for this account")
    @GetMapping("/{accountId}/analytics")
    public ResponseEntity<?> getAccountAnalytics(
            @Parameter(description = "Account UUID", required = true)
            @PathVariable UUID accountId,
            HttpServletRequest request) {
        UUID userId = extractUserId(request);
        var analytics = queryService.getAnalytics(accountId, userId);
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