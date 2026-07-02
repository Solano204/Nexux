package com.nexus.kyc.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.kyc.application.pipeline.Stage1DocumentExtraction;
import com.nexus.kyc.application.pipeline.Stage2DataComparison;
import com.nexus.kyc.application.validation.DocumentQualityValidator;
import com.nexus.kyc.application.validation.HardRuleValidator;
import com.nexus.kyc.domain.exception.DocumentQualityException;
import com.nexus.kyc.domain.model.KycExtractedData;
import com.nexus.kyc.domain.model.KycVerificationDecision;
import com.nexus.kyc.domain.model.KycVerificationRequest;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.domain.model.enums.RejectionReason;
import com.nexus.kyc.infrastructure.jpa.KycAuditRepository;
import com.nexus.kyc.infrastructure.kafka.KycEventProducer;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentMongoDB;
import com.nexus.kyc.infrastructure.mongodb.KycDocumentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.nexus.kyc.infrastructure.jpa.KycAuditEntryJPA;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KYC Verification Service — Main pipeline orchestrator.
 */
@Slf4j
@Service
public class KycVerificationService {

    // ── Pipeline stages ───────────────────────────────────────
    private final Stage1DocumentExtraction stage1;
    private final Stage2DataComparison stage2;
    private final HardRuleValidator hardRuleValidator;
    private final DocumentQualityValidator qualityValidator;

    // ── Persistence ───────────────────────────────────────────
    private final KycDocumentRepository kycDocumentRepository;
    private final KycAuditRepository auditRepository;

    // ── Messaging ─────────────────────────────────────────────
    private final KycEventProducer eventProducer;

    // ── Observability ─────────────────────────────────────────
    private final ObservationRegistry observationRegistry;
    private final Timer pipelineTimer;
    private final Counter approvedCounter;
    private final Counter rejectedCounter;
    private final Counter reviewCounter;
    private final Counter hardRejectCounter;

    // ── JSON ───────────────────────────────────────────────────
    private final ObjectMapper objectMapper;

    public KycVerificationService(
            Stage1DocumentExtraction stage1,
            Stage2DataComparison stage2,
            HardRuleValidator hardRuleValidator,
            DocumentQualityValidator qualityValidator,
            KycDocumentRepository kycDocumentRepository,
            KycAuditRepository auditRepository,
            KycEventProducer eventProducer,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper) {  // ✅ Added ObjectMapper parameter

        this.stage1 = stage1;
        this.stage2 = stage2;
        this.hardRuleValidator = hardRuleValidator;
        this.qualityValidator = qualityValidator;
        this.kycDocumentRepository = kycDocumentRepository;
        this.auditRepository = auditRepository;
        this.eventProducer = eventProducer;
        this.observationRegistry = observationRegistry;
        this.objectMapper = objectMapper;  // ✅ Initialize ObjectMapper

        this.pipelineTimer = Timer.builder("kyc.pipeline.total.duration")
                .description("End-to-end KYC pipeline duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.approvedCounter = Counter.builder("kyc.decisions.total")
                .tag("outcome", "APPROVED")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("kyc.decisions.total")
                .tag("outcome", "REJECTED")
                .register(meterRegistry);
        this.reviewCounter = Counter.builder("kyc.decisions.total")
                .tag("outcome", "REVIEW_REQUIRED")
                .register(meterRegistry);
        this.hardRejectCounter = Counter.builder("kyc.decisions.total")
                .tag("outcome", "HARD_REJECT")
                .register(meterRegistry);
    }

    // ── Public API ────────────────────────────────────────────

    @Transactional
    public KycDocumentMongoDB verify(
            KycVerificationRequest request,
            byte[] imageBytes,
            String mimeType,
            String sagaId) {

        Observation obs = Observation.createNotStarted(
                        "kyc.pipeline", observationRegistry)
                .lowCardinalityKeyValue("documentType",
                        request.documentType().name())
                .start();

        Timer.Sample sample = Timer.start();
        String verificationId = UUID.randomUUID().toString();

        log.info("KYC pipeline start: verificationId={} userId={}",
                verificationId, request.userId());

        try (Observation.Scope scope = obs.openScope()) {

            // ── Step 1: Document quality ──────────────────
            DocumentQualityValidator.QualityCheckResult quality =
                    qualityValidator.validate(imageBytes, mimeType);

            if (!quality.passed()) {
                return persistAndPublish(
                        buildQualityFailureDoc(
                                verificationId, request, sagaId,
                                quality.issues()),
                        null, request, sagaId);
            }

            // ── Step 2: Hard rules ────────────────────────
            int previousAttempts = countPreviousAttempts(request.userId());
            HardRuleValidator.HardRuleResult hardRules =
                    hardRuleValidator.validate(request, previousAttempts);

            if (!hardRules.passed()) {
                hardRejectCounter.increment();
                obs.event(Observation.Event.of("kyc.hard.reject"));
                return persistAndPublish(
                        buildHardRejectDoc(
                                verificationId, request, sagaId,
                                hardRules.failures()),
                        null, request, sagaId);
            }

            // ── Step 3: Stage 1 — Vision extraction ───────
            long s1Start = System.currentTimeMillis();
            KycExtractedData extracted;

            try {
                extracted = stage1.extract(
                        new ByteArrayResource(imageBytes),
                        mimeType,
                        request.documentType().name());
            } catch (DocumentQualityException e) {
                return persistAndPublish(
                        buildDocQualityRejectDoc(
                                verificationId, request, sagaId,
                                e.getReasons()),
                        null, request, sagaId);
            }

            long s1Duration = System.currentTimeMillis() - s1Start;

            // ── Step 4: Stage 2 — Data comparison ─────────
            long s2Start = System.currentTimeMillis();
            KycVerificationDecision decision =
                    stage2.compare(request, extracted);
            long s2Duration = System.currentTimeMillis() - s2Start;

            // ── Step 5: Update decision counters ──────────
            switch (decision.status()) {
                case APPROVED       -> approvedCounter.increment();
                case REJECTED       -> rejectedCounter.increment();
                case REVIEW_REQUIRED -> reviewCounter.increment();
                default             -> {}
            }
            obs.lowCardinalityKeyValue("outcome", decision.status().name());

            // ── Step 6: Build operational document ────────
            KycDocumentMongoDB doc = KycDocumentMongoDB.builder()
                    .verificationId(verificationId)
                    .userId(request.userId())
                    .status(decision.status())
                    .documentType(request.documentType())
                    .attemptNumber(previousAttempts + 1)
                    .maxAttempts(3)
                    .submittedData(buildSubmittedData(request))
                    .extractedData(extracted)
                    .stage1DurationMs(s1Duration)
                    .stage1Model("gpt-4o")
                    .decision(decision)
                    .stage2DurationMs(s2Duration)
                    .stage2Model("gpt-4o-mini")
                    .documentHash(sha256(imageBytes))
                    .passedHardRules(true)
                    .sagaId(sagaId)
                    .sagaStatus("DECIDED")
                    .submittedAt(Instant.now())
                    .decidedAt(Instant.now())
                    .expiresAt(Instant.now().plus(Duration.ofDays(90)))
                    .processingTiming(KycDocumentMongoDB.ProcessingTiming.builder()
                            .stage1AiMs(s1Duration)
                            .stage2AiMs(s2Duration)
                            .totalMs(s1Duration + s2Duration)
                            .build())
                    .build();

            return persistAndPublish(doc, extracted, request, sagaId);

        } finally {
            sample.stop(pipelineTimer);
            obs.stop();
        }
    }

    // ── Persistence + event publication ──────────────────────

    private KycDocumentMongoDB persistAndPublish(
            KycDocumentMongoDB doc,
            KycExtractedData extracted,
            KycVerificationRequest request,
            String sagaId) {

        kycDocumentRepository.save(doc);
        persistAuditEntry(doc, extracted, request);
        eventProducer.publishKycDecision(doc, sagaId);

        log.info("KYC pipeline complete: verificationId={} status={} userId={}",
                doc.getVerificationId(), doc.getStatus(), doc.getUserId());

        return doc;
    }

    /**
     * Writes immutable audit record to PostgreSQL.
     * PII fields stored as SHA-256 hashes — never plaintext.
     */
    /**
     * Writes immutable audit record to PostgreSQL.
     * PII fields stored as SHA-256 hashes — never plaintext.
     */
    private void persistAuditEntry(
            KycDocumentMongoDB doc,
            KycExtractedData extracted,
            KycVerificationRequest request) {

        String[] rejectionReasons = (doc.getDecision() != null &&
                doc.getDecision().rejectionReasons() != null)
                ? doc.getDecision().rejectionReasons()
                .stream()
                .map(Enum::name)
                .toArray(String[]::new)
                : new String[0];

        // ✅ Get field matches from the decision (Stage 2 result)
        String fieldMatchesJson = extractFieldMatchesAsJson(doc.getDecision());

        KycAuditEntryJPA audit = KycAuditEntryJPA.builder()
                .auditId(UUID.randomUUID())
                .userId(UUID.fromString(doc.getUserId()))
                .verificationId(UUID.fromString(doc.getVerificationId()))
                .attemptNumber(doc.getAttemptNumber())
                .documentType(doc.getDocumentType().name())
                .stage1ExtractionConfidence(
                        extracted != null ? extracted.overallConfidence() : null)
                .stage1DurationMs((int) doc.getStage1DurationMs())
                .stage2ComparisonConfidence(
                        doc.getDecision() != null
                                ? doc.getDecision().confidenceScore() : null)
                .stage2DurationMs((int) doc.getStage2DurationMs())
                .decision(doc.getStatus().name())
                .rejectionReasons(rejectionReasons)
                .userFacingMessage(
                        doc.getDecision() != null
                                ? doc.getDecision().userFacingRejectionMessage()
                                : null)
                .confidenceScore(
                        doc.getDecision() != null
                                ? doc.getDecision().confidenceScore() : null)
                .fieldMatches(fieldMatchesJson)  // ✅ Now using decision's field matches
                .submittedNameHash(sha256str(request.fullName()))
                .submittedDobHash(sha256str(request.dateOfBirth()))
                .extractedNameHash(
                        extracted != null && extracted.extractedFullName() != null
                                ? sha256str(extracted.extractedFullName())
                                : null)
                .extractedDobHash(
                        extracted != null && extracted.extractedDateOfBirth() != null
                                ? sha256str(extracted.extractedDateOfBirth())
                                : null)
                .aiModelStage1(doc.getStage1Model())
                .aiModelStage2(doc.getStage2Model())
                .processingDurationMs((int) (doc.getStage1DurationMs() + doc.getStage2DurationMs()))
                .submittedAt(doc.getSubmittedAt())
                .decidedAt(doc.getDecidedAt())
                .retentionUntil(Instant.now().plus(Duration.ofDays(365 * 7)))
                .build();

        auditRepository.save(audit);
    }

    /**
     * Extract field matches from the verification decision (Stage 2 result)
     */
    private String extractFieldMatchesAsJson(KycVerificationDecision decision) {
        if (decision == null || decision.fieldMatches() == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(decision.fieldMatches());
        } catch (Exception e) {
            log.warn("Failed to serialize field matches", e);
            return null;
        }
    }
    // ── Early-exit document builders ─────────────────────────

    private KycDocumentMongoDB buildQualityFailureDoc(
            String verificationId,
            KycVerificationRequest req,
            String sagaId,
            List<String> issues) {

        String userMsg = "es".equals(req.language())
                ? "El archivo enviado no es una imagen válida. Por favor sube una foto en formato JPEG o PNG."
                : "The uploaded file is not a valid image. Please upload a JPEG or PNG photo.";

        return KycDocumentMongoDB.builder()
                .verificationId(verificationId)
                .userId(req.userId())
                .status(KycStatus.REJECTED)
                .documentType(req.documentType())
                .attemptNumber(countPreviousAttempts(req.userId()) + 1)
                .maxAttempts(3)
                .passedHardRules(false)
                .hardRuleFailures(issues)
                .submittedData(buildSubmittedData(req))
                .decision(new KycVerificationDecision(
                        KycStatus.REJECTED,
                        1.0,
                        Map.of(),
                        List.of(RejectionReason.DOCUMENT_UNREADABLE),
                        userMsg,
                        true,
                        0,
                        null, null, null, null,
                        "Document quality check failed: " + issues,
                        false,
                        null))
                .submittedAt(Instant.now())
                .decidedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(90)))
                .sagaId(sagaId)
                .sagaStatus("DECIDED")
                .build();
    }

    private KycDocumentMongoDB buildHardRejectDoc(
            String verificationId,
            KycVerificationRequest req,
            String sagaId,
            List<String> failures) {

        boolean canRetry = !failures.contains("MAX_ATTEMPTS_EXCEEDED")
                && !failures.contains("UNDERAGE");

        return KycDocumentMongoDB.builder()
                .verificationId(verificationId)
                .userId(req.userId())
                .status(KycStatus.REJECTED)
                .documentType(req.documentType())
                .attemptNumber(countPreviousAttempts(req.userId()) + 1)
                .maxAttempts(3)
                .passedHardRules(false)
                .hardRuleFailures(failures)
                .submittedData(buildSubmittedData(req))
                .decision(new KycVerificationDecision(
                        KycStatus.REJECTED,
                        1.0,
                        Map.of(),
                        List.of(RejectionReason.DOCUMENT_NOT_SUPPORTED),
                        buildUserMessage(failures, req.language()),
                        canRetry,
                        canRetry ? 0 : -1,
                        null, null, null, null,
                        "Hard rule failure: " + failures,
                        false,
                        null))
                .submittedAt(Instant.now())
                .decidedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(90)))
                .sagaId(sagaId)
                .sagaStatus("DECIDED")
                .build();
    }

    private KycDocumentMongoDB buildDocQualityRejectDoc(
            String verificationId,
            KycVerificationRequest req,
            String sagaId,
            List<RejectionReason> reasons) {

        String userMsg = "es".equals(req.language())
                ? "No pudimos leer tu documento. Por favor toma una foto más clara con buena iluminación."
                : "We could not read your document. Please take a clearer photo with good lighting.";

        return KycDocumentMongoDB.builder()
                .verificationId(verificationId)
                .userId(req.userId())
                .status(KycStatus.REJECTED)
                .documentType(req.documentType())
                .attemptNumber(countPreviousAttempts(req.userId()) + 1)
                .maxAttempts(3)
                .passedHardRules(true)
                .submittedData(buildSubmittedData(req))
                .decision(new KycVerificationDecision(
                        KycStatus.REJECTED,
                        0.0,
                        Map.of(),
                        reasons,
                        userMsg,
                        true,
                        0,
                        null, null, null, null,
                        "Stage 1 quality check failed",
                        false,
                        null))
                .submittedAt(Instant.now())
                .decidedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(90)))
                .sagaId(sagaId)
                .sagaStatus("DECIDED")
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────

    private int countPreviousAttempts(String userId) {
        return (int) kycDocumentRepository.countByUserId(userId);
    }

    private KycDocumentMongoDB.SubmittedData buildSubmittedData(
            KycVerificationRequest req) {
        return KycDocumentMongoDB.SubmittedData.builder()
                .fullName(req.fullName())
                .dateOfBirth(req.dateOfBirth())
                .documentType(req.documentType().name())
                .documentNumber(req.documentNumber())
                .nationality(req.nationality())
                .language(req.language())
                .build();
    }

    private String buildUserMessage(List<String> failures, String language) {
        boolean es = "es".equals(language);

        if (failures.contains("UNDERAGE")) {
            return es
                    ? "Debes tener al menos 18 años para abrir una cuenta."
                    : "You must be at least 18 years old to open an account.";
        }
        if (failures.contains("MAX_ATTEMPTS_EXCEEDED")) {
            return es
                    ? "Has superado el límite de intentos. Por favor contacta soporte."
                    : "You have exceeded the attempt limit. Please contact support.";
        }
        if (failures.contains("DOCUMENT_EXPIRED")) {
            return es
                    ? "Tu documento está vencido. Por favor usa un documento vigente."
                    : "Your document is expired. Please use a current document.";
        }
        return es
                ? "No pudimos procesar tu solicitud. Por favor intenta nuevamente."
                : "We could not process your request. Please try again.";
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("SHA-256 computation failed", e);
            return "hash-error";
        }
    }

    private String sha256str(String data) {
        if (data == null) return null;
        return sha256(data.getBytes(StandardCharsets.UTF_8));
    }
}