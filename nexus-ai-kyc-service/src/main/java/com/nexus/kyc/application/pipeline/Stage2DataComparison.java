package com.nexus.kyc.application.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.kyc.domain.model.*;
import com.nexus.kyc.domain.model.enums.KycStatus;
import com.nexus.kyc.domain.model.enums.RejectionReason;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Stage 2 — Data Comparison.
 *
 * Receives:
 * - KycVerificationRequest: what the user submitted
 * - KycExtractedData: what the AI read from the image
 *
 * Calls GPT-4o-mini (cheaper than Stage 1 — no vision needed)
 * with structured output to produce KycVerificationDecision.
 *
 * The AI handles the hard part:
 * - Name normalization across cultural conventions
 * - Date format normalization
 * - Fuzzy matching for near-matches
 * - Forgery indicator integration into the decision
 *
 * Temperature 0.0: decisions must be consistent and reproducible.
 * Two identical inputs must always produce the same decision.
 */
@Slf4j
@Component
public class Stage2DataComparison {

    private final ChatClient comparisonClient;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Timer comparisonTimer;
    private final RateLimiter openAiRateLimiter;

    // Testing-only bypass for when there's no working OpenAI key: skips the
    // real comparison call entirely and always approves, so the KYC
    // pipeline can be exercised end-to-end without AI. Set
    // nexus.ai.mock-mode=true (nexus-platform-config) to enable.
    @Value("${nexus.ai.mock-mode:false}")
    private boolean mockMode;

    public Stage2DataComparison(
            @Qualifier("kycStage2ComparisonClient")
            ChatClient comparisonClient,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry,
            RateLimiter openAiRateLimiter) {

        this.comparisonClient = comparisonClient;
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.openAiRateLimiter = openAiRateLimiter;
        this.comparisonTimer = Timer.builder(
                        "kyc.stage2.comparison.duration")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Compares submitted data against extracted data.
     *
     * Builds a rich comparison prompt with both data sets,
     * including any quality warnings from Stage 1.
     *
     * @return KycVerificationDecision with APPROVED/REJECTED/REVIEW_REQUIRED
     */
    public KycVerificationDecision compare(
            KycVerificationRequest submitted,
            KycExtractedData extracted) {

        Observation obs = Observation.createNotStarted(
                "kyc.stage2.compare", observationRegistry).start();

        Timer.Sample sample = Timer.start();

        try (Observation.Scope scope = obs.openScope()) {

            String comparisonPrompt = buildComparisonPrompt(
                    submitted, extracted);

            log.info("Stage 2 comparison: name='{}' dob='{}'",
                    submitted.fullName(), submitted.dateOfBirth());

            // Section 3: structured output
            KycVerificationDecision decision = mockMode
                    ? buildMockApprovedDecision(submitted)
                    : RateLimiter.decorateSupplier(openAiRateLimiter, () ->
                            comparisonClient.prompt()
                                    .user(comparisonPrompt)
                                    .call()
                                    .entity(KycVerificationDecision.class)).get();

            if (decision == null) {
                return buildFailureDecision(
                        "Stage 2 returned null",
                        submitted.language());
            }

            obs.lowCardinalityKeyValue("status",
                    decision.status().name());

            log.info("Stage 2 decision: status={} confidence={}",
                    decision.status(), decision.confidenceScore());

            return decision;

        } catch (Exception e) {
            obs.error(e);
            log.error("Stage 2 comparison failed: {}",
                    e.getMessage(), e);
            return buildFailureDecision(
                    e.getMessage(), submitted.language());
        } finally {
            sample.stop(comparisonTimer);
            obs.stop();
        }
    }

    /**
     * Builds the comparison prompt with both data sets.
     * Structured as a clear comparison table for the LLM.
     */
    private String buildComparisonPrompt(
            KycVerificationRequest submitted,
            KycExtractedData extracted) {

        return """
            Compare the following identity information.
            Language for user-facing messages: %s

            ═══ USER SUBMITTED DATA ═══
            Full Name:         %s
            Date of Birth:     %s
            Document Number:   %s
            Document Type:     %s
            Nationality:       %s

            ═══ AI EXTRACTED FROM DOCUMENT ═══
            Full Name:         %s
            Date of Birth:     %s
            Document Number:   %s
            Document Type:     %s
            Issuing Country:   %s
            MRZ Present:       %s
            MRZ Consistent:    %s
            Extraction Confidence: %.2f
            Unreadable Fields: %s

            ═══ QUALITY FLAGS ═══
            Document Expired:  %s
            Forgery Detected:  %s  ← true=forgery found, false=no forgery (clean document)
            Forgery Detail:    %s
            Image Issues:      %s

            ═══ TASK ═══
            1. Compare each submitted field against extracted field
            2. Apply name normalization rules for Latin American names
            3. Apply date normalization (different formats may be same date)
            4. Apply the decision rules from the system prompt in order
            5. Produce KycVerificationDecision JSON

            Be specific in fieldMatches about WHY each field matches
            or does not match.
            """.formatted(
                submitted.language() != null
                        ? submitted.language() : "es",
                submitted.fullName(),
                submitted.dateOfBirth(),
                submitted.documentNumber(),
                submitted.documentType(),
                submitted.nationality() != null
                        ? submitted.nationality() : "not provided",
                extracted.extractedFullName() != null
                        ? extracted.extractedFullName() : "UNREADABLE",
                extracted.extractedDateOfBirth() != null
                        ? extracted.extractedDateOfBirth() : "UNREADABLE",
                extracted.documentNumber() != null
                        ? extracted.documentNumber() : "UNREADABLE",
                extracted.detectedDocumentType() != null
                        ? extracted.detectedDocumentType() : "UNKNOWN",
                extracted.issuingCountry() != null
                        ? extracted.issuingCountry() : "UNKNOWN",
                extracted.mrzPresent(),
                extracted.mrzPresent()
                        ? extracted.mrzConsistentWithVisualZone()
                        : "N/A",
                extracted.overallConfidence(),
                extracted.unreadableFields() != null
                        ? extracted.unreadableFields() : "none",
                extracted.isDocumentExpired(),
                extracted.isForgeryIndicatorPresent(),
                extracted.forgeryIndicatorDetail() != null
                        ? extracted.forgeryIndicatorDetail() : "none",
                extracted.imageQualityIssues() != null
                        ? extracted.imageQualityIssues() : "none"
        );
    }

    private KycVerificationDecision buildMockApprovedDecision(
            KycVerificationRequest submitted) {
        return new KycVerificationDecision(
                KycStatus.APPROVED,
                0.99,
                Map.of(),
                List.of(),
                null,
                true,
                0,
                submitted.fullName(),
                submitted.dateOfBirth(),
                submitted.documentNumber(),
                submitted.nationality(),
                "nexus.ai.mock-mode=true - synthetic approval, no comparison call was made.",
                false,
                null);
    }

    private KycVerificationDecision buildFailureDecision(
            String reason, String language) {

        String userMessage = "es".equals(language)
                ? "No pudimos procesar tu verificación en este momento. " +
                "Por favor intenta nuevamente."
                : "We could not process your verification at this time. " +
                "Please try again.";

        return new KycVerificationDecision(
                KycStatus.FAILED,
                0.0,
                java.util.Map.of(),
                List.of(RejectionReason.AI_EXTRACTION_FAILED),
                userMessage,
                true,   // canRetry
                0,      // no delay
                null, null, null, null,
                "Technical failure: " + reason,
                false, null
        );
    }
}