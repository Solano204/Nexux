package com.nexus.kyc.web.controller;

import com.nexus.kyc.application.KycVerificationService;
import com.nexus.kyc.domain.model.KycVerificationRequest;
import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentMongoDB;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentRepository;
import com.nexus.kyc.web.controller.dto.response.StatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/**
 * KYC Controller — User-facing verification endpoints.
 *
 * POST /api/v1/kyc/verify
 *   Accepts: multipart/form-data
 *   Parts:   document (image), fullName, dateOfBirth,
 *            documentNumber, documentType, language (opt)
 *   Returns: {@link KycVerificationResult}
 *
 * GET /api/v1/kyc/status/{verificationId}
 *   Returns: current status + user-facing message for a verification
 *
 * Security: X-User-Id header injected by nexus-api-gateway after JWT
 * validation — this controller trusts it without re-validating.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kyc")
@RequiredArgsConstructor
@Tag(name = "KYC", description = "Identity document verification for the caller's own user — AWS Rekognition + LLM comparison pipeline.")
@SecurityRequirement(name = "X-User-Id")
public class KycController {

    private final KycVerificationService verificationService;
    private final KycDocumentRepository kycDocumentRepository;

    // ── POST /api/v1/kyc/verify ───────────────────────────────

    @Operation(
            summary = "Submit a document for KYC verification",
            description = "Deliberately returns only user-actionable fields (userFacingMessage, " +
                    "canRetry) — confidence scores, AI reasoning, and which specific fields failed " +
                    "comparison never leave this response, by design. On any internal failure this " +
                    "still returns 200 with a REJECTED-shaped error body rather than a 5xx — check " +
                    "requiresAction/status, not just HTTP status, to detect failure here."
    )
    @ApiResponse(responseCode = "200", description = "Verification processed (check status field — this includes rejections and internal failures, not just success)")
    @ApiResponse(responseCode = "400", description = "Invalid documentType value")
    @PostMapping(
            value = "/verify",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<KycVerificationResult> verify(
            @Parameter(description = "Photo of the identity document", required = true)
            @RequestPart("document") MultipartFile documentFile,
            @Parameter(description = "Full legal name as on the document", required = true)
            @RequestPart("fullName") @NotBlank String fullName,
            @Parameter(description = "ISO date, e.g. 1990-01-01", required = true)
            @RequestPart("dateOfBirth") @NotBlank String dateOfBirth,
            @Parameter(description = "Document number as printed", required = true)
            @RequestPart("documentNumber") @NotBlank String documentNumber,
            @Parameter(description = "e.g. PASSPORT, NATIONAL_ID, DRIVERS_LICENSE", required = true)
            @RequestPart("documentType") String documentType,
            @Parameter(description = "ISO country code, optional")
            @RequestPart(value = "nationality", required = false)
            String nationality,
            @Parameter(description = "Language for user-facing messages, defaults to es")
            @RequestPart(value = "language", required = false)
            String language,
            @Parameter(description = "Set by the gateway, not sent by the client directly in production", required = true)
            @RequestHeader("X-User-Id") String userId) {

        try {
            KycVerificationRequest request = new KycVerificationRequest(
                    userId,
                    fullName,
                    dateOfBirth,
                    documentNumber,
                    DocumentType.valueOf(documentType.toUpperCase()),
                    nationality,
                    language != null ? language : "es");

            KycDocumentMongoDB result = verificationService.verify(
                    request,
                    documentFile.getBytes(),
                    documentFile.getContentType(),
                    UUID.randomUUID().toString());   // new saga correlation ID

            return ResponseEntity.ok(KycVerificationResult.from(result));

        } catch (IllegalArgumentException e) {
            // documentType not a valid enum value
            log.warn("KYC verify — invalid documentType: {} userId={}",
                    documentType, userId);
            return ResponseEntity.badRequest()
                    .body(KycVerificationResult.error(
                            "Invalid document type: " + documentType));

        } catch (Exception e) {
            log.error("KYC verify error: userId={} error={}",
                    userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(KycVerificationResult.error(
                            "Verification failed. Please try again."));
        }
    }

    // ── GET /api/v1/kyc/status/{verificationId} ───────────────

    @Operation(summary = "Get my verification status", description = "Composite-key lookup (verificationId + userId) — a verification belonging to another user 404s rather than leaking that it exists.")
    @ApiResponse(responseCode = "200", description = "Status retrieved")
    @ApiResponse(responseCode = "404", description = "No verification with this ID for this user")
    @GetMapping("/status/{verificationId}")
    public ResponseEntity<StatusResponse> getStatus(
            @Parameter(description = "Verification ID, from the verify response", required = true)
            @PathVariable String verificationId,
            @Parameter(description = "Set by the gateway, not sent by the client directly in production", required = true)
            @RequestHeader("X-User-Id") String userId) {

        return kycDocumentRepository
                .findByVerificationIdAndUserId(verificationId, userId)
                .map(doc -> {
                    String userFacingMessage = "";
                    boolean canRetry = false;

                    if (doc.getDecision() != null) {
                        userFacingMessage = doc.getDecision()
                                .userFacingRejectionMessage() != null
                                ? doc.getDecision().userFacingRejectionMessage()
                                : "";
                        canRetry = doc.getDecision().canRetry();
                    }

                    StatusResponse response = new StatusResponse(
                            doc.getVerificationId(),
                            doc.getStatus().name(),
                            doc.getSubmittedAt().toString(),
                            doc.getDecidedAt() != null ? doc.getDecidedAt().toString() : "",
                            userFacingMessage,
                            canRetry,
                            doc.getStatus() == KycStatus.REVIEW_REQUIRED
                    );

                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Response DTO ──────────────────────────────────────────

    /**
     * User-facing verification result.
     *
     * Deliberately omits internal fields (confidence scores,
     * AI reasoning, rejected field names) — users see only
     * what they need to act on.
     */
    public record KycVerificationResult(
            String verificationId,
            String status,
            String userFacingMessage,
            boolean canRetry,
            int retryDelayHours,
            boolean requiresAction
    ) {

        /** Build from a persisted {@link KycDocumentMongoDB}. */
        static KycVerificationResult from(KycDocumentMongoDB doc) {
            String message = "";
            boolean retry = false;
            int delay = 0;

            if (doc.getDecision() != null) {
                message = doc.getDecision().userFacingRejectionMessage() != null
                        ? doc.getDecision().userFacingRejectionMessage()
                        : "";
                retry = doc.getDecision().canRetry();
                delay = doc.getDecision().retryDelayHours();
            }

            return new KycVerificationResult(
                    doc.getVerificationId(),
                    doc.getStatus().name(),
                    message,
                    retry,
                    delay,
                    doc.getStatus() == KycStatus.REVIEW_REQUIRED
            );
        }

        /** Generic error response when the pipeline itself throws. */
        static KycVerificationResult error(String message) {
            return new KycVerificationResult(
                    null,
                    KycStatus.REJECTED.name(),
                    message,
                    true,
                    0,
                    false
            );
        }
    }
}