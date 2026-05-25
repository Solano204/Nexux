package com.nexus.kyc.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.kyc.application.pipeline.*;
import com.nexus.kyc.application.validation.*;
import com.nexus.kyc.domain.exception.DocumentQualityException;
import com.nexus.kyc.domain.model.*;
import com.nexus.kyc.domain.model.enums.*;
import com.nexus.kyc.infrastructure.jpa.KycAuditRepository;
import com.nexus.kyc.infrastructure.jpa.KycAuditEntry;
import com.nexus.kyc.infrastructure.mongodb.*;
import com.nexus.kyc.infrastructure.kafka.KycEventProducer;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * KYC Verification Service — Main pipeline orchestrator.
 *
 * Processing pipeline:
 *
 * 1. Document quality check (byte-level, free)
 * 2. Hard rule validation (deterministic, free)
 * 3. Stage 1: AI vision extraction (GPT-4o, ~$0.015/image)
 * 4. Stage 2: AI comparison (GPT-4o-mini, ~$0.001/call)
 * 5. Write MongoDB operational document
 * 6. Write PostgreSQL immutable audit entry
 * 7. Publish Kafka events (outbox pattern)
 *
 * Cost optimization: steps 1-2 prevent spending on AI for
 * clearly invalid requests (wrong file type, underage, etc.)
 *
 * SAGA: Part of OnboardingFlowSaga.
 * Receives: StartKycVerificationCommand
 * Replies: KycApprovedReply or KycRejectedReply via outbox → Kafka
 */
@Slf4j
@Service
public class KycVerificationService {

    private final Stage1DocumentExtraction stage1;
    private final Stage2DataComparison stage2;
    private final HardRuleValidator hardRuleValidator;
    private final DocumentQualityValidator qualityValidator;
    private final KycDocumentRepository kycDocumentRepository;
    private final KycAuditRepository auditRepository;
    private final KycEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;

    private final Timer pipelineTimer;
    private final Counter approvedCounter;
    private final Counter rejectedCounter;
    private final Counter reviewCounter;
    private final Counter hardRejectCounter;

    public KycVerificationService(
            Stage1DocumentExtraction stage1,
            Stage2DataComparison stage2,
            HardRuleValidator hardRuleValidator,
            DocumentQualityValidator qualityValidator,
            KycDocumentRepository kycDocumentRepository,
            KycAuditRepository auditRepository,
            KycEventProducer eventProducer,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.stage1 = stage1;
        this.stage2 = stage2;
        this.hardRuleValidator = hardRuleValidator;
        this.qualityValidator = qualityValidator;
        this.kycDocumentRepository = kycDocumentRepository;
        this.auditRepository = auditRepository;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;

        this.pipelineTimer = Timer.builder(
                        "kyc.pipeline.total.duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);

        this.approvedCounter = Counter.builder("kyc.decisions.total")
                .tag("outcome", "APPROVED").register(meterRegistry);
        this.rejectedCounter = Counter.builder("kyc.decisions.total")
                .tag("outcome", "REJECTED").register(meterRegistry);
        this.reviewCounter = Counter.builder("kyc.decisions.total")
                .tag("outcome", "REVIEW_REQUIRED").register(meterRegistry);
        this.hardRejectCounter = Counter.builder("kyc.decisions.total")
                .tag("outcome", "HARD_REJECT").register(meterRegistry);
    }

    /**
     * Full KYC verification pipeline.
     *
     * @param request    User-submitted identity data
     * @param imageBytes Document image bytes
     * @param mimeType   Image MIME type
     * @param sagaId     Saga correlation ID
     * @return KycDocument with full pipeline results
     */
    @Transactional
    public KycDocument verify(KycVerificationRequest request,
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

            // ── 1. Document quality check ─────────────────
            DocumentQualityValidator.QualityCheckResult quality =
                    qualityValidator.validate(imageBytes, mimeType);

            if (!quality.passed()) {
                return persistAndPublish(
                        buildQualityFailureDocument(
                                verificationId, request, sagaId,
                                quality.issues()),
                        null, request, sagaId);
            }

            // ── 2. Hard rule validation ───────────────────
            int previousAttempts = countPreviousAttempts(
                    request.userId());

            HardRuleValidator.HardRuleResult hardRules =
                    hardRuleValidator.validate(
                            request, previousAttempts);

            if (!hardRules.passed()) {
                hardRejectCounter.increment();
                obs.event(Observation.Event.of("kyc.hard.reject"));
                return persistAndPublish(
                        buildHardRejectDocument(
                                verificationId, request, sagaId,
                                hardRules.failures()),
                        null, request, sagaId);
            }

            // ── 3. Stage 1: Vision extraction ─────────────
            long s1Start = System.currentTimeMillis();

            KycExtractedData extracted;
            try {
                extracted = stage1.extract(
                        new ByteArrayResource(imageBytes),
                        mimeType,
                        request.documentType().name());
            } catch (DocumentQualityException e) {
                return persistAndPublish(
                        buildDocumentQualityRejectDocument(
                                verificationId, request, sagaId,
                                e.getReasons()),
                        null, request, sagaId);
            }

            long s1Duration = System.currentTimeMillis() - s1Start;

            // ── 4. Stage 2: Data comparison ───────────────
            long s2Start = System.currentTimeMillis();

            KycVerificationDecision decision =
                    stage2.compare(request, extracted);

            long s2Duration = System.currentTimeMillis() - s2Start;

            // ── 5. Update metrics ─────────────────────────
            switch (decision.status()) {
                case APPROVED -> approvedCounter.increment();
                case REJECTED -> rejectedCounter.increment();
                case REVIEW_REQUIRED -> reviewCounter.increment();
                default -> {}
            }

            obs.lowCardinalityKeyValue("outcome",
                    decision.status().name());

            // ── 6. Build operational document ─────────────
            KycDocument doc = KycDocument.builder()
                    .verificationId(verificationId)
                    .userId(request.userId())
                    .status(decision.status())
                    .documentType(request.documentType())
                    .attemptNumber(previousAttempts + 1)
                    .maxAttempts(3)
                    .extractedData(extracted)
                    .stage1DurationMs(s1Duration)
                    .decision(decision)
                    .stage2DurationMs(s2Duration)
                    .documentHash(sha256(imageBytes))
                    .stage1Model("gpt-4o")
                    .stage2Model("gpt-4o-mini")
                    .passedHardRules(true)
                    .submittedAt(Instant.now())
                    .decidedAt(Instant.now())
                    .expiresAt(Instant.now()
                            .plus(java.time.Duration.ofDays(90)))
                    .sagaId(sagaId)
                    .sagaStatus("DECIDED")
                    .build();

            return persistAndPublish(doc, extracted, request, sagaId);

        } finally {
            sample.stop(pipelineTimer);
            obs.stop();
        }
    }

    // ── Persistence + event publication ──────────────────────

    private KycDocument persistAndPublish(
            KycDocument doc,
            KycExtractedData extracted,
            KycVerificationRequest request,
            String sagaId) {

        // Persist MongoDB operational document
        kycDocumentRepository.save(doc);

        // Persist PostgreSQL immutable audit entry
        persistAuditEntry(doc, extracted, request);

        // Publish outbox events
        eventProducer.publishKycDecision(doc, sagaId);

        log.info("KYC pipeline complete: verificationId={} " +
                        "status={} userId={}",
                doc.getVerificationId(), doc.getStatus(),
                doc.getUserId());

        return doc;
    }

    private void persistAuditEntry(
            KycDocument doc,
            KycExtractedData extracted,
            KycVerificationRequest request) {

        KycAuditEntry audit = KycAuditEntry.builder()
                .auditId(UUID.randomUUID())
                .userId(UUID.fromString(doc.getUserId()))
                .verificationId(UUID.fromString(doc.getVerificationId()))
                .attemptNumber(doc.getAttemptNumber())
                .documentType(doc.getDocumentType().name())
                .stage1ExtractionConfidence(
                        extracted != null
                                ? extracted.overallConfidence() : null)
                .stage1DurationMs((int) doc.getStage1DurationMs())
                .stage2ComparisonConfidence(
                        doc.getDecision() != null
                                ? doc.getDecision().confidenceScore() : null)
                .stage2DurationMs((int) doc.getStage2DurationMs())
                .decision(doc.getStatus().name())
                .rejectionReasons(
                        doc.getDecision() != null &&
                                doc.getDecision().rejectionReasons() != null
                                ? doc.getDecision().rejectionReasons()
                                .stream()
                                .map(Enum::name)
                                .toArray(String[]::new)
                                : new String[0])
                .userFacingMessage(
                        doc.getDecision() != null
                                ? doc.getDecision().userFacingRejectionMessage()
                                : null)
                .confidenceScore(
                        doc.getDecision() != null
                                ? doc.getDecision().confidenceScore() : null)
                .submittedNameHash(sha256str(request.fullName()))
                .submittedDobHash(sha256str(request.dateOfBirth()))
                .extractedNameHash(
                        extracted != null &&
                                extracted.extractedFullName() != null
                                ? sha256str(extracted.extractedFullName())
                                : null)
                .extractedDobHash(
                        extracted != null &&
                                extracted.extractedDateOfBirth() != null
                                ? sha256str(extracted.extractedDateOfBirth())
                                : null)
                .aiModelStage1(doc.getStage1Model())
                .aiModelStage2(doc.getStage2Model())
                .submittedAt(doc.getSubmittedAt())
                .decidedAt(doc.getDecidedAt())
                .retentionUntil(Instant.now()
                        .plus(java.time.Duration.ofDays(365 * 7)))
                .build();

        auditRepository.save(audit);
    }

    // ── Document builders for early-exit scenarios ────────────

    private KycDocument buildQualityFailureDocument(
            String verificationId,
            KycVerificationRequest req,
            String sagaId,
            List<String> issues) {

        return KycDocument.builder()
                .verificationId(verificationId)
                .userId(req.userId())
                .status(KycStatus.REJECTED)
                .documentType(req.documentType())
                .attemptNumber(1)
                .maxAttempts(3)
                .passedHardRules(false)
                .hardRuleFailures(issues)
                .decision(new KycVerificationDecision(
                        KycStatus.REJECTED, 0.0,
                        Map.of(),
                        List.of(RejectionReason.DOCUMENT_UNREADABLE),
                        buildUserMessage(issues, req.language()),
                        true, 0,
                        null, null, null, null,
                        "Document quality check failed: " + issues,
                        false, null))
                .submittedAt(Instant.now())
                .decidedAt(Instant.now())
                .sagaId(sagaId)
                .build();
    }

    private KycDocument buildHardRejectDocument(
            String verificationId,
            KycVerificationRequest req,
            String sagaId,
            List<String> failures) {

        boolean canRetry = !failures.contains(
                "MAX_ATTEMPTS_EXCEEDED") &&
                !failures.contains("UNDERAGE");

        return KycDocument.builder()
                .verificationId(verificationId)
                .userId(req.userId())
                .status(KycStatus.REJECTED)
                .documentType(req.documentType())
                .attemptNumber(1)
                .passedHardRules(false)
                .hardRuleFailures(failures)
                .decision(new KycVerificationDecision(
                        KycStatus.REJECTED, 1.0,
                        Map.of(),
                        List.of(RejectionReason.DOCUMENT_NOT_SUPPORTED),
                        buildUserMessage(failures, req.language()),
                        canRetry, canRetry ? 0 : -1,
                        null, null, null, null,
                        "Hard rule failure: " + failures,
                        false, null))
                .submittedAt(Instant.now())
                .decidedAt(Instant.now())
                .sagaId(sagaId)
                .build();
    }

    private KycDocument buildDocumentQualityRejectDocument(
            String verificationId,
            KycVerificationRequest req,
            String sagaId,
            List<RejectionReason> reasons) {

        String userMsg = "es".equals(req.language())
                ? "No pudimos leer tu documento. " +
                "Por favor toma una foto más clara con buena iluminación."
                : "We could not read your document. " +
                "Please take a clearer photo with good lighting.";

        return KycDocument.builder()
                .verificationId(verificationId)
                .userId(req.userId())
                .status(KycStatus.REJECTED)
                .documentType(req.documentType())
                .attemptNumber(
                        countPreviousAttempts(req.userId()) + 1)
                .passedHardRules(true)
                .decision(new KycVerificationDecision(
                        KycStatus.REJECTED, 0.0, Map.of(),
                        reasons, userMsg, true, 0,
                        null, null, null, null,
                        "Stage 1 quality check failed",
                        false, null))
                .submittedAt(Instant.now())
                .decidedAt(Instant.now())
                .sagaId(sagaId)
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────

    private int countPreviousAttempts(String userId) {
        return (int) kycDocumentRepository
                .countByUserId(userId);
    }

    private String buildUserMessage(List<String> failures,
                                    String language) {
        boolean es = "es".equals(language);

        if (failures.contains("UNDERAGE")) {
            return es
                    ? "Debes tener al menos 18 años para abrir una cuenta."
                    : "You must be at least 18 years old to open an account.";
        }
        if (failures.contains("MAX_ATTEMPTS_EXCEEDED")) {
            return es
                    ? "Has superado el límite de intentos. " +
                    "Por favor contacta soporte."
                    : "You have exceeded the attempt limit. " +
                    "Please contact support.";
        }
        if (failures.contains("DOCUMENT_EXPIRED")) {
            return es
                    ? "Tu documento está vencido. " +
                    "Por favor usa un documento vigente."
                    : "Your document is expired. " +
                    "Please use a current document.";
        }
        return es
                ? "No pudimos procesar tu solicitud. " +
                "Por favor intenta nuevamente."
                : "We could not process your request. Please try again.";
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "hash-error";
        }
    }

    private String sha256str(String data) {
        if (data == null) return null;
        return sha256(data.getBytes(StandardCharsets.UTF_8));
    }
}