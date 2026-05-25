package com.nexus.kyc.application.pipeline;

import com.nexus.kyc.domain.model.KycExtractedData;
import com.nexus.kyc.domain.model.enums.RejectionReason;
import com.nexus.kyc.domain.exception.DocumentQualityException;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

/**
 * Stage 1 — Document Extraction.
 *
 * Implements Section 8 image-to-text pattern:
 * - Sends document image to GPT-4o vision model
 * - Structured output: entity(KycExtractedData.class)
 * - Temperature 0.0: exact extraction, no creativity
 *
 * Post-extraction validation:
 * - Confidence threshold check (minimum 0.60)
 * - Expiry date check
 * - Forgery indicator check
 * - Unreadable fields assessment
 *
 * On failure: Resilience4j @Retry (3x with exponential backoff).
 * The vision API can have transient failures on large images.
 */
@Slf4j
@Component
public class Stage1DocumentExtraction {

    private final ChatClient extractionClient;
    private final ObservationRegistry observationRegistry;
    private final Timer extractionTimer;

    public Stage1DocumentExtraction(
            @Qualifier("kycStage1ExtractionClient")
            ChatClient extractionClient,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {

        this.extractionClient = extractionClient;
        this.observationRegistry = observationRegistry;
        this.extractionTimer = Timer.builder(
                        "kyc.stage1.extraction.duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Extracts identity data from a document image.
     *
     * @param documentImage Spring Resource wrapping the image bytes
     * @param mimeType      image/jpeg, image/png, etc.
     * @param documentType  Expected document type (hint for the model)
     * @return KycExtractedData fully populated record
     * @throws DocumentQualityException if image is too poor to process
     */
    @Retry(name = "kyc-vision-retry")
    public KycExtractedData extract(Resource documentImage,
                                    String mimeType,
                                    String documentType) {

        Observation obs = Observation.createNotStarted(
                "kyc.stage1.extract", observationRegistry).start();

        Timer.Sample sample = Timer.start();

        try (Observation.Scope scope = obs.openScope()) {

            log.info("Stage 1 extraction: documentType={}",
                    documentType);

            // Section 8: image-to-text multimodal call
            KycExtractedData extracted = extractionClient.prompt()
                    .user(u -> {
                        u.text("""
                        Extract all identity information from this
                        %s document image.
                        Be thorough and accurate.
                        Report exactly what you see, not what you think
                        it should say.
                        """.formatted(documentType));
                        // Attach the document image as media content
                        u.media(MimeType.valueOf(mimeType),
                                documentImage);
                    })
                    .call()
                    .entity(KycExtractedData.class);  // Section 3

            if (extracted == null) {
                throw new KycProcessingException(
                        "Stage 1 returned null response");
            }

            obs.lowCardinalityKeyValue("confidence",
                    confidenceBand(extracted.overallConfidence()));

            log.info("Stage 1 complete: confidence={} " +
                            "documentType={} expired={} forgery={}",
                    extracted.overallConfidence(),
                    extracted.detectedDocumentType(),
                    extracted.isDocumentExpired(),
                    extracted.isForgeryIndicatorPresent());

            validateExtractionQuality(extracted);

            return extracted;

        } catch (DocumentQualityException e) {
            obs.error(e);
            throw e;
        } catch (Exception e) {
            obs.error(e);
            log.error("Stage 1 extraction failed: {}",
                    e.getMessage(), e);
            throw new KycProcessingException(
                    "Document extraction failed: " + e.getMessage(), e);
        } finally {
            sample.stop(extractionTimer);
            obs.stop();
        }
    }

    /**
     * Post-extraction quality validation.
     * Throws DocumentQualityException for issues that prevent
     * Stage 2 comparison — no point running comparison on a
     * document we can't read.
     */
    private void validateExtractionQuality(
            KycExtractedData data) {

        if (data.overallConfidence()
        KycExtractedData.MINIMUM_CONFIDENCE_THRESHOLD) {
            throw new DocumentQualityException(
                    "Document image quality too low: confidence=" +
                            data.overallConfidence(),
                    buildQualityRejectionReasons(data));
        }

        if (data.isDocumentExpired()) {
            throw new DocumentQualityException(
                    "Document is expired: " +
                            data.extractedExpiryDate(),
                    java.util.List.of(RejectionReason.DOCUMENT_EXPIRED));
        }

        // Forgery indicators: do NOT reject immediately —
        // flag for Stage 2 which will include this in decision.
        // Rejection on forgery suspicion happens in Stage 2
        // so the comparison result is also in the audit trail.
        if (data.isForgeryIndicatorPresent()) {
            log.warn("Forgery indicator detected: userId=unknown " +
                    "detail={}", data.forgeryIndicatorDetail());
        }
    }

    private java.util.List<RejectionReason> buildQualityRejectionReasons(
            KycExtractedData data) {

        var reasons = new java.util.ArrayList<RejectionReason>();

        if (data.imageQualityIssues() != null) {
            data.imageQualityIssues().forEach(issue -> {
                switch (issue) {
                    case "BLURRY" ->
                            reasons.add(RejectionReason.IMAGE_BLURRY);
                    case "TOO_DARK" ->
                            reasons.add(RejectionReason.IMAGE_TOO_DARK);
                    case "PARTIAL" ->
                            reasons.add(RejectionReason.IMAGE_PARTIAL);
                    case "GLARE" ->
                            reasons.add(RejectionReason.DOCUMENT_GLARE);
                }
            });
        }

        if (reasons.isEmpty()) {
            reasons.add(RejectionReason.DOCUMENT_UNREADABLE);
        }

        return reasons;
    }

    private String confidenceBand(double confidence) {
        if (confidence >= 0.90) return "HIGH";
        if (confidence >= 0.75) return "MEDIUM";
        if (confidence >= 0.60) return "LOW";
        return "INSUFFICIENT";
    }
}

// Supporting exception classes

package com.nexus.kyc.domain.exception;

import com.nexus.kyc.domain.model.enums.RejectionReason;
import java.util.List;

public class DocumentQualityException extends RuntimeException {
    private final List<RejectionReason> reasons;
    public DocumentQualityException(String message,
                                    List<RejectionReason> reasons) {
        super(message);
        this.reasons = reasons;
    }
    public List<RejectionReason> getReasons() { return reasons; }
}

public class KycProcessingException extends RuntimeException {
    public KycProcessingException(String message) { super(message); }
    public KycProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}