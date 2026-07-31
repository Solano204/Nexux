package com.nexus.gateway.featureflag;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Manual override (resiliencia guide, Fase 7). Lets a human intervene
 * ahead of the automatic circuit-breaker-driven disable in
 * AiFeatureHealthMonitor, or manually re-enable after confirming a
 * dependency actually recovered — the recovery half of Fase 7 that's
 * deliberately NOT automated.
 *
 * IP-restricted inline (172.20.0.0/16, same Docker-internal convention as
 * every other /internal/v1 route) rather than via a route-level
 * RemoteAddr predicate: this controller is served directly by WebFlux's
 * own dispatch, not through the RouteLocator, so the gateway's per-route
 * predicates never see it — without this check it would be reachable from
 * outside the internal network with nothing else guarding it.
 */
@RestController
@RequestMapping("/internal/v1/feature-flags")
@RequiredArgsConstructor
@Tag(name = "Feature Flags (Internal)", description = "Manual AI-feature kill switch — IP-restricted inline (172.20.0.0/16 + localhost), not a header-based scheme, since this controller bypasses the gateway's own route predicates. See class Javadoc.")
public class FeatureFlagAdminController {

    private final FeatureFlagService featureFlagService;

    @Operation(summary = "Disable a feature", description = "Manual override ahead of (or instead of) the automatic circuit-breaker-driven disable — reason is recorded for whoever re-enables it later.")
    @ApiResponse(responseCode = "200", description = "Feature disabled")
    @ApiResponse(responseCode = "403", description = "Caller is outside the Docker-internal network")
    @PostMapping("/{feature}/disable")
    public Mono<ResponseEntity<Map<String, String>>> disable(
            @Parameter(description = "Feature flag name", required = true)
            @PathVariable String feature,
            @Parameter(description = "Why it's being disabled, for the audit trail")
            @RequestParam(defaultValue = "Manual override") String reason,
            ServerWebExchange exchange) {
        if (!isInternal(exchange)) return forbidden();
        return featureFlagService.disable(feature, reason)
                .thenReturn(ResponseEntity.ok(
                        Map.of("feature", feature, "status", "disabled", "reason", reason)));
    }

    @Operation(summary = "Re-enable a feature", description = "The recovery half of Fase 7 that's deliberately NOT automated — a human confirms the dependency actually recovered before flipping this back on.")
    @ApiResponse(responseCode = "200", description = "Feature enabled")
    @ApiResponse(responseCode = "403", description = "Caller is outside the Docker-internal network")
    @PostMapping("/{feature}/enable")
    public Mono<ResponseEntity<Map<String, String>>> enable(
            @Parameter(description = "Feature flag name", required = true)
            @PathVariable String feature, ServerWebExchange exchange) {
        if (!isInternal(exchange)) return forbidden();
        return featureFlagService.enable(feature)
                .thenReturn(ResponseEntity.ok(Map.of("feature", feature, "status", "enabled")));
    }

    @Operation(summary = "Get a feature flag's status", description = "Whether it's enabled, and if not, the recorded reason (automatic disable or manual override).")
    @ApiResponse(responseCode = "200", description = "Status retrieved")
    @ApiResponse(responseCode = "403", description = "Caller is outside the Docker-internal network")
    @GetMapping("/{feature}")
    public Mono<ResponseEntity<Map<String, Object>>> status(
            @Parameter(description = "Feature flag name", required = true)
            @PathVariable String feature, ServerWebExchange exchange) {
        if (!isInternal(exchange)) return forbidden();
        return featureFlagService.isEnabled(feature)
                .flatMap(enabled -> enabled
                        ? Mono.just(ResponseEntity.ok(Map.of("feature", feature, "enabled", true)))
                        : featureFlagService.disabledReason(feature)
                                .map(reason -> ResponseEntity.ok(Map.of(
                                        "feature", feature, "enabled", false, "reason", reason))));
    }

    private boolean isInternal(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return false;
        String ip = remote.getAddress().getHostAddress();
        return ip.startsWith("172.20.") || ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    private <T> Mono<ResponseEntity<T>> forbidden() {
        return Mono.just(ResponseEntity.status(403).build());
    }
}
