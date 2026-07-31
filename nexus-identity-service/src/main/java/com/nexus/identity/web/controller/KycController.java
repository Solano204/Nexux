package com.nexus.identity.web.controller;

import com.nexus.identity.application.command.UnauthorizedException;
import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.application.query.UserQueryService;
import com.nexus.identity.web.dto.response.*;
import io.micrometer.tracing.Tracer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 *
 * The AI KYC service result used to arrive here via a POST
 * /internal/v1/users/{userId}/kyc/result callback — replaced by
 * KycResultConsumer (topic identity.kyc.result, Outbox+Debezium from
 * ai-kyc-service) — see CHANGES-BESTPRACTICES/08_EVENT_DESIGN_CHANGES.md
 * Section 6.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "KYC", description = "Identity verification — document upload kicks off nexus-ai-kyc-service's async pipeline (Rekognition + LLM comparison), result arrives back via Kafka, not synchronously in this response.")
@SecurityRequirement(name = "X-User-Id")
public class KycController {

    private final UserCommandService commandService;
    private final UserQueryService queryService;
    private final Tracer tracer;

    @Operation(
            summary = "Submit a KYC document for verification",
            description = "Returns 202 immediately — verification runs asynchronously in " +
                    "nexus-ai-kyc-service. Poll getKycStatus for the outcome, don't expect it in " +
                    "this response."
    )
    @ApiResponse(responseCode = "202", description = "Document accepted, verification started")
    @ApiResponse(responseCode = "400", description = "Missing required field or unsupported document type")
    @PostMapping(
            value = "/api/v1/users/me/kyc/initiate",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<KycInitiationResponse> initiateKyc(
            @Parameter(description = "Photo of the identity document", required = true)
            @RequestParam("document") MultipartFile document,
            @Parameter(description = "e.g. PASSPORT, NATIONAL_ID, DRIVERS_LICENSE", required = true)
            @RequestParam("documentType") String documentType,
            @Parameter(description = "Full legal name as it appears on the document", required = true)
            @RequestParam("fullName") String fullName,
            @Parameter(description = "ISO date, e.g. 1990-01-01", required = true)
            @RequestParam("dateOfBirth") String dateOfBirth,
            @Parameter(description = "Document number as printed", required = true)
            @RequestParam("documentNumber") String documentNumber,
            @Parameter(description = "ISO country code, optional")
            @RequestParam(value = "nationality", required = false) String nationality,
            @Parameter(description = "Language for user-facing rejection messages, defaults to es")
            @RequestParam(value = "language", required = false) String language,
            HttpServletRequest request) throws Exception {

        UUID userId = extractUserId(request);
        String traceId = getTraceId();
        String ipAddress = getClientIp(request);

        KycInitiationResponse response = commandService.initiateKyc(
                userId, document, documentType,
                fullName, dateOfBirth, documentNumber, nationality, language,
                ipAddress, traceId);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(summary = "Get my KYC status", description = "Current verification status plus, when rejected, a user-facing reason and whether a retry is allowed.")
    @ApiResponse(responseCode = "200", description = "Status retrieved")
    @GetMapping("/api/v1/users/me/kyc/status")
    public ResponseEntity<KycStatusResponse> getKycStatus(
            HttpServletRequest request) {

        UUID userId = extractUserId(request);
        return ResponseEntity.ok(queryService.getCurrentKycStatus(userId));
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