package com.nexus.kyc.application.pipeline;

import com.nexus.kyc.domain.exception.DocumentQualityException;
import com.nexus.kyc.domain.exception.KycProcessingException;
import com.nexus.kyc.domain.model.KycExtractedData;
import com.nexus.kyc.domain.model.enums.DocumentType;
import com.nexus.kyc.domain.model.enums.RejectionReason;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 — Document Extraction.
 *
 * Implements Section 8 image-to-text pattern:
 * - Sends document image to GPT-4o-mini vision model
 * - Structured output via .entity(KycExtractedData.class)
 * - Temperature 0.0: exact extraction, no creativity
 *
 * Post-extraction validation:
 * - Confidence threshold check (minimum 0.60)
 * - Expiry date check
 * - Forgery indicator check (flagged but not hard-rejected here —
 *   Stage 2 includes it in the audit trail decision)
 * - Unreadable fields assessment
 *
 * On transient vision API failure: Resilience4j @Retry (3x with
 * exponential backoff configured in application.yml).
 */
@Slf4j
@Component
public class Stage1DocumentExtraction {

    private final ChatClient extractionClient;
    private final ObservationRegistry observationRegistry;
    private final Timer extractionTimer;
    private final RateLimiter openAiRateLimiter;

    // Testing-only bypass for when there's no working OpenAI key: skips the
    // real vision call entirely and returns a synthetic high-confidence
    // extraction, so the KYC pipeline can be exercised end-to-end without
    // AI. Set nexus.ai.mock-mode=true (nexus-platform-config) to enable.
    @Value("${nexus.ai.mock-mode:false}")
    private boolean mockMode;

    public Stage1DocumentExtraction(
            @Qualifier("kycStage1ExtractionClient")
            ChatClient extractionClient,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry,
            RateLimiter openAiRateLimiter) {

        this.extractionClient  = extractionClient;
        this.observationRegistry = observationRegistry;
        this.openAiRateLimiter = openAiRateLimiter;
        this.extractionTimer = Timer.builder("kyc.stage1.extraction.duration")
                .description("Time taken for Stage 1 document extraction")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Extracts identity data from a document image.
     *
     * @param documentImage Spring Resource wrapping the raw image bytes
     * @param mimeType      MIME type string: "image/jpeg", "image/png", etc.
     * @param documentType  Expected document type hint for the model
     *                      (e.g. "PASSPORT", "NATIONAL_ID", "DRIVING_LICENSE")
     * @return KycExtractedData fully populated record
     * @throws DocumentQualityException if the image quality is too low
     *                                  or the document is expired
     * @throws KycProcessingException   on any unexpected AI pipeline failure
     */
    @Retry(name = "kyc-vision-retry")
    public KycExtractedData extract(Resource documentImage,
                                    String mimeType,
                                    String documentType) {

        Observation obs = Observation
                .createNotStarted("kyc.stage1.extract", observationRegistry)
                .start();

        Timer.Sample sample = Timer.start();

        try (Observation.Scope ignored = obs.openScope()) {

            log.info("Stage 1 extraction started: documentType={}", documentType);

            // Section 8: multimodal image-to-text call.
            // .entity(KycExtractedData.class) instructs Spring AI to
            // parse the model's JSON response into the record (Section 3).
            KycExtractedData extracted = mockMode
                    ? buildMockExtractedData(documentType)
                    : RateLimiter.decorateSupplier(openAiRateLimiter, () ->
                            extractionClient.prompt()
                                    .user(u -> {
                                        u.text("""
                                                Extract all identity information from this
                                                %s document image.
                                                Be thorough and accurate.
                                                Report exactly what you see, not what you
                                                think it should say.
                                                """.formatted(documentType));
                                        u.media(MimeType.valueOf(mimeType), documentImage);
                                    })
                                    .call()
                                    .entity(KycExtractedData.class)).get();

            if (extracted == null) {
                throw new KycProcessingException(
                        "Stage 1 returned null response — model produced no output");
            }

            obs.lowCardinalityKeyValue(
                    "confidence", confidenceBand(extracted.overallConfidence()));

            log.info("Stage 1 complete: confidence={} documentType={} " +
                            "expired={} forgery={}",
                    extracted.overallConfidence(),
                    extracted.detectedDocumentType(),
                    extracted.isDocumentExpired(),
                    extracted.isForgeryIndicatorPresent());

            // Validate quality before handing off to Stage 2.
            // Throws DocumentQualityException on hard failures.
            validateExtractionQuality(extracted);

            return extracted;

        } catch (DocumentQualityException e) {
            obs.error(e);
            throw e;                        // propagate as-is — already typed
        } catch (KycProcessingException e) {
            obs.error(e);
            throw e;                        // propagate as-is
        } catch (Exception e) {
            obs.error(e);
            log.error("Stage 1 extraction failed unexpectedly: {}",
                    e.getMessage(), e);
            throw new KycProcessingException(
                    "Document extraction failed: " + e.getMessage(), e);
        } finally {
            sample.stop(extractionTimer);
            obs.stop();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Mock mode (nexus.ai.mock-mode=true) — no real vision call
    // ─────────────────────────────────────────────────────────────────────

    private KycExtractedData buildMockExtractedData(String documentType) {
        return new KycExtractedData(
                DocumentType.valueOf(documentType),
                "MX", "MOCK-DOC-0000",
                "MOCK USER", "MOCK", "USER",
                "01/01/1990", "01/01/2030",
                "MX", "X", null,
                null, null, false, true,
                0.99, 0.99, 0.99, List.of(),
                false, false, null,
                List.of(),
                "nexus.ai.mock-mode=true - synthetic extraction, no vision call was made.");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Post-extraction quality gate.
     *
     * Hard rejects (throw immediately):
     * - Confidence below MINIMUM_CONFIDENCE_THRESHOLD
     * - Document expired
     *
     * Soft flag (log + continue to Stage 2):
     * - Forgery indicator — Stage 2 includes this in the audit decision
     */
    private void validateExtractionQuality(KycExtractedData data) {

        // FIX: was missing the '<' operator — caused compile error
        if (data.overallConfidence() < KycExtractedData.MINIMUM_CONFIDENCE_THRESHOLD) {
            throw new DocumentQualityException(
                    "Document image quality too low: confidence=" +
                            data.overallConfidence(),
                    buildQualityRejectionReasons(data));
        }

        if (data.isDocumentExpired()) {
            throw new DocumentQualityException(
                    "Document is expired: expiry=" + data.extractedExpiryDate(),
                    List.of(RejectionReason.DOCUMENT_EXPIRED));
        }

        // Forgery: flag only — Stage 2 will include it in the decision
        // so the comparison result is also captured in the audit trail.
        if (data.isForgeryIndicatorPresent()) {
            log.warn("Forgery indicator detected — forwarding to Stage 2. " +
                            "detail={}",
                    data.forgeryIndicatorDetail());
        }
    }

    /**
     * Maps image quality issue strings returned by the model to typed
     * RejectionReasons. Returns at least DOCUMENT_UNREADABLE if the
     * model reported issues but none matched the known codes.
     */
    private List<RejectionReason> buildQualityRejectionReasons(
            KycExtractedData data) {

        List<RejectionReason> reasons = new ArrayList<>();

        if (data.imageQualityIssues() != null) {
            for (String issue : data.imageQualityIssues()) {
                switch (issue) {
                    case "BLURRY"   -> reasons.add(RejectionReason.IMAGE_BLURRY);
                    case "TOO_DARK" -> reasons.add(RejectionReason.IMAGE_TOO_DARK);
                    case "PARTIAL"  -> reasons.add(RejectionReason.IMAGE_PARTIAL);
                    case "GLARE"    -> reasons.add(RejectionReason.DOCUMENT_GLARE);
                    default         -> log.debug(
                            "Unknown image quality issue code: {}", issue);
                }
            }
        }

        if (reasons.isEmpty()) {
            reasons.add(RejectionReason.DOCUMENT_UNREADABLE);
        }

        return reasons;
    }

    /**
     * Buckets a 0.0–1.0 confidence score into a low-cardinality
     * label suitable for Micrometer tags.
     */
    private String confidenceBand(double confidence) {
        if (confidence >= 0.90) return "HIGH";
        if (confidence >= 0.75) return "MEDIUM";
        if (confidence >= 0.60) return "LOW";
        return "INSUFFICIENT";
    }
}