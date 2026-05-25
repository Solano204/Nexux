package com.nexus.kyc.web.controller;

import com.nexus.kyc.application.KycVerificationService;
import com.nexus.kyc.domain.model.*;
import com.nexus.kyc.domain.model.enums.DocumentType;
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
 * KYC Controller — User-facing verification endpoint.
 *
 * POST /api/v1/kyc/verify
 *   Accepts: multipart/form-data
 *   Parts: document (image file), fullName, dateOfBirth,
 *          documentNumber, documentType, language
 *   Returns: KycVerificationResult
 *
 * GET /api/v1/kyc/status/{verificationId}
 *   Returns current status of a verification
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kyc")
@RequiredArgsConstructor
public class KycController {

    private final KycVerificationService verificationService;
    private final com.nexus.kyc.infrastructure.mongodb
            .KycDocumentRepository kycDocumentRepository;

    @PostMapping(
            value = "/verify",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<KycVerificationResult> verify(
            @RequestPart("document") MultipartFile documentFile,
            @RequestPart("fullName") @NotBlank String fullName,
            @RequestPart("dateOfBirth") @NotBlank String dateOfBirth,
            @RequestPart("documentNumber") @NotBlank
            String documentNumber,
            @RequestPart("documentType") String documentType,
            @RequestPart(value = "nationality", required = false)
            String nationality,
            @RequestPart(value = "language", required = false)
            String language,
            @RequestHeader("X-User-Id") String userId) {

        try {
            KycVerificationRequest request =
                    new KycVerificationRequest(
                            userId, fullName, dateOfBirth,
                            documentNumber,
                            DocumentType.valueOf(
                                    documentType.toUpperCase()),
                            nationality,
                            language != null ? language : "es");

            com.nexus.kyc.infrastructure.mongodb.KycDocument result =
                    verificationService.verify(
                            request,
                            documentFile.getBytes(),
                            documentFile.getContentType(),
                            UUID.randomUUID().toString()); // New SAGA ID

            return ResponseEntity.ok(
                    KycVerificationResult.from(result));

        } catch (Exception e) {
            log.error("KYC verify error: userId={} {}",
                    userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(KycVerificationResult.error(
                            "Verification failed. Please try again."));
        }
    }

    @GetMapping("/status/{verificationId}")
    public ResponseEntity<Map<String, Object>> getStatus(
            @PathVariable String verificationId,
            @RequestHeader("X-User-Id") String userId) {

        return kycDocumentRepository
                .findByVerificationIdAndUserId(
                        verificationId, userId)
                .map(doc -> ResponseEntity.ok(Map.of(
                        "verificationId", doc.getVerificationId(),
                        "status", doc.getStatus().name(),
                        "submittedAt", doc.getSubmittedAt().toString(),
                        "decidedAt", doc.getDecidedAt() != null
                                ? doc.getDecidedAt().toString() : null,
                        "userFacingMessage",
                        doc.getDecision() != null &&
                                doc.getDecision().userFacingRejectionMessage()
                                        != null
                                ? doc.getDecision().userFacingRejectionMessage()
                                : "",
                        "canRetry",
                        doc.getDecision() != null &&
                                doc.getDecision().canRetry()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    public record KycVerificationResult(
            String verificationId,
            String status,
            String userFacingMessage,
            boolean canRetry,
            int retryDelayHours,
            boolean requiresAction
    ) {
        static KycVerificationResult from(
                com.nexus.kyc.infrastructure.mongodb
                        .KycDocument doc) {
            return new KycVerificationResult(
                    doc.getVerificationId(),
                    doc.getStatus().name(),
                    doc.getDecision() != null
                            ? doc.getDecision()
                            .userFacingRejectionMessage() : null,
                    doc.getDecision() != null &&
                            doc.getDecision().canRetry(),
                    doc.getDecision() != null
                            ? doc.getDecision().retryDelayHours() : 0,
                    doc.getStatus() == KycStatus.REVIEW_REQUIRED
            );
        }

        static KycVerificationResult error(String message) {
            return new KycVerificationResult(
                    null, "FAILED", message,
                    true, 0, false);
        }
    }
}