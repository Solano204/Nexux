package com.nexus.identity.web.controller;

import com.nexus.identity.application.command.UnauthorizedException;
import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.application.query.UserQueryService;
import com.nexus.identity.web.dto.request.KycResultRequest;
import com.nexus.identity.web.dto.response.*;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * KYC Controller — Identity verification endpoints.
 *
 * POST /api/v1/users/me/kyc/initiate  — Upload document
 * GET  /api/v1/users/me/kyc/status    — Check status
 * POST /internal/v1/users/{userId}/kyc/result — AI KYC service callback
 */
@RestController
@RequiredArgsConstructor
public class KycController {

    private final UserCommandService commandService;
    private final UserQueryService queryService;
    private final Tracer tracer;

    @PostMapping(
            value = "/api/v1/users/me/kyc/initiate",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<KycInitiationResponse> initiateKyc(
            @RequestParam("document") MultipartFile document,
            @RequestParam("documentType") String documentType,
            HttpServletRequest request) throws Exception {

        UUID userId = extractUserId(request);
        String traceId = getTraceId();
        String ipAddress = getClientIp(request);

        KycInitiationResponse response = commandService.initiateKyc(
                userId, document, documentType, ipAddress, traceId);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/api/v1/users/me/kyc/status")
    public ResponseEntity<KycStatusResponse> getKycStatus(
            HttpServletRequest request) {

        UUID userId = extractUserId(request);
        return ResponseEntity.ok(queryService.getCurrentKycStatus(userId));
    }

    /**
     * Internal callback from nexus-ai-kyc-service.
     * Delivers verification decision after AI analysis.
     * IP-restricted at gateway level to Docker network only.
     */
    @PostMapping("/internal/v1/users/{userId}/kyc/result")
    public ResponseEntity<Void> receiveKycResult(
            @PathVariable UUID userId,
            @RequestBody KycResultRequest result,
            HttpServletRequest request) {

        String traceId = getTraceId();
        commandService.processKycResult(
                userId, UUID.fromString(result.verificationId()),
                result, traceId);

        return ResponseEntity.ok().build();
    }

    private UUID extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null) {
            throw new UnauthorizedException("X-User-Id header missing");
        }
        return UUID.fromString(userId);
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