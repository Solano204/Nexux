package com.nexus.kyc.web.controller;

import com.nexus.kyc.application.KycVerificationService;
import com.nexus.kyc.domain.model.KycVerificationRequest;
import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentMongoDB;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentRepository;
import com.nexus.kyc.web.controller.dto.response.StatusResponse;
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
public class KycController {

    private final KycVerificationService verificationService;
    private final KycDocumentRepository kycDocumentRepository;

    // ── POST /api/v1/kyc/verify ───────────────────────────────

    @PostMapping(
            value = "/verify",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<KycVerificationResult> verify(
            @RequestPart("document") MultipartFile documentFile,
            @RequestPart("fullName") @NotBlank String fullName,
            @RequestPart("dateOfBirth") @NotBlank String dateOfBirth,
            @RequestPart("documentNumber") @NotBlank String documentNumber,
            @RequestPart("documentType") String documentType,
            @RequestPart(value = "nationality", required = false)
            String nationality,
            @RequestPart(value = "language", required = false)
            String language,
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

    @GetMapping("/status/{verificationId}")
    public ResponseEntity<StatusResponse> getStatus(
            @PathVariable String verificationId,
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