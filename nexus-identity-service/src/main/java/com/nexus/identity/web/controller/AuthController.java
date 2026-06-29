package com.nexus.identity.web.controller;

import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.infrastructure.jwt.JwksEndpointProvider;
import com.nexus.identity.web.dto.request.*;
import com.nexus.identity.web.dto.response.*;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Auth Controller — Public authentication endpoints.
 *
 * Routes (all public — no JWT required):
 * POST /api/v1/auth/register
 * POST /api/v1/auth/login
 * POST /api/v1/auth/refresh-token
 * POST /api/v1/auth/logout
 * GET  /api/v1/auth/.well-known/jwks.json
 * POST /api/v1/auth/password-reset/request
 * POST /api/v1/auth/password-reset/confirm
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserCommandService commandService;
    private final JwksEndpointProvider jwksProvider;
    private final Tracer tracer;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {

        String traceId = getTraceId();
        String ip = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        RegisterResponse response = commandService.register(
                request, ip, userAgent, traceId);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        String traceId = getTraceId();
        String ip = getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        LoginResponse response = commandService.login(
                request, ip, userAgent, traceId);

        // Set refresh token as HttpOnly Secure cookie
        // NOT in response body — prevents XSS from stealing it
        ResponseCookie refreshCookie = ResponseCookie
                .from("refreshToken", response.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(Duration.ofDays(30))
                .path("/api/v1/auth/refresh-token")  // Scoped path
                .build();

        // Return response WITHOUT refresh token in body
        LoginResponse sanitizedResponse = new LoginResponse(
                response.accessToken(),
                null,  // Don't expose refresh token in body
                response.expiresIn(),
                response.tokenType(),
                response.userId(),
                response.roles()
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie.toString())
                .body(sanitizedResponse);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            HttpServletRequest httpRequest) {

        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No token provided"));
        }

        String traceId = getTraceId();

        try {
            com.auth0.jwt.interfaces.DecodedJWT decoded =
                    com.auth0.jwt.JWT.decode(authHeader.substring(7));

            String userId = decoded.getSubject();
            String jti = decoded.getId();
            java.time.Instant expiresAt = decoded.getExpiresAtAsInstant();

            if (userId != null && jti != null && expiresAt != null) {
                commandService.logout(
                        java.util.UUID.fromString(userId),
                        jti,
                        expiresAt,
                        getClientIp(httpRequest),
                        traceId
                );
            }
        } catch (Exception e) {
            log.warn("Logout: failed to decode token: {}", e.getMessage());
        }

        // Clear refresh token cookie
        ResponseCookie clearCookie = ResponseCookie
                .from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(Duration.ZERO)
                .path("/api/v1/auth/refresh-token")
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                .body(Map.of("message", "Logged out successfully"));
    }

    /**
     * JWKS endpoint — Public key distribution for JWT verification.
     * Cached aggressively by API Gateway (1-hour cache).
     */
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> getJwks() {
        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=3600")
                .body(jwksProvider.getJwks());
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<Map<String, String>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request) {

        // Always return 200 — prevents user enumeration
        // If email exists, sends reset link. If not, silently ignores.
        try {
            commandService.requestPasswordReset(request.email());
        } catch (Exception e) {
            log.warn("Password reset request error (suppressed): {}",
                    e.getMessage());
        }

        return ResponseEntity.ok(Map.of(
                "message",
                "If this email is registered, a password reset link has been sent."
        ));
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