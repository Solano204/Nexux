package com.nexus.identity.infrastructure.ai;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * KYC Rejection Explainer — Spring AI Section 3 (Structured Output).
 *
 * Translates technical KYC rejection codes into
 * user-friendly explanations in Spanish.
 *
 * Uses GPT-4o-mini (cheap, fast, sufficient for translation task).
 * Returns structured KycRejectionExplanation record.
 *
 * Example:
 * Input: ["FACE_BLURRY", "DOCUMENT_EXPIRED"]
 * Output: {
 *   userMessage: "Tu documento no pudo verificarse porque...",
 *   actionItems: ["Toma una foto más clara", "Obtén un documento vigente"]
 * }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KycRejectionExplainer {

    private final ChatClient kycExplainerClient;
    private final ObservationRegistry observationRegistry;

    /**
     * Translates rejection codes to user-friendly message.
     *
     * @param rejectionCodes Technical codes from AI KYC service
     * @param language       "es" for Spanish, "en" for English
     * @return User-friendly explanation string
     */
    public String explain(List<String> rejectionCodes, String language) {

        if (rejectionCodes == null || rejectionCodes.isEmpty()) {
            return defaultRejectionMessage(language);
        }

        Observation obs = Observation.createNotStarted(
                "identity.ai.kyc.explain", observationRegistry).start();

        try {
            KycRejectionExplanation explanation = kycExplainerClient
                    .prompt()
                    .system("""
                    You are a customer service assistant for a Mexican digital bank.
                    Your job is to translate technical KYC (identity verification)
                    rejection codes into clear, empathetic messages for users.

                    Rules:
                    - Always respond in the requested language
                    - Be empathetic and helpful, not accusatory
                    - Provide specific, actionable next steps
                    - Keep the message concise (2-3 sentences max)
                    - Return ONLY valid JSON matching the schema
                    """)
                    .user(u -> u.text("""
                    Translate these KYC rejection codes into a friendly message.
                    Language: {language}
                    Rejection codes: {codes}

                    Return JSON with:
                    - userMessage: String (friendly explanation)
                    - actionItems: String[] (what to do next, max 3 items)
                    """)
                            .param("language",
                                    "es".equals(language) ? "Spanish" : "English")
                            .param("codes", String.join(", ", rejectionCodes)))
                    .call()
                    .entity(KycRejectionExplanation.class);

            obs.event(Observation.Event.of("ai.explain.success"));
            return explanation.userMessage();

        } catch (Exception e) {
            obs.error(e);
            log.warn("AI rejection explanation failed, using fallback: {}",
                    e.getMessage());
            return defaultRejectionMessage(language);
        } finally {
            obs.stop();
        }
    }

    private String defaultRejectionMessage(String language) {
        return "es".equals(language)
                ? "Tu verificación de identidad no pudo completarse. " +
                "Por favor intenta de nuevo con un documento vigente y " +
                "una fotografía clara."
                : "Your identity verification could not be completed. " +
                "Please try again with a valid document and clear photo.";
    }

    /**
     * Structured output record for Spring AI entity mapping.
     * Section 3: Structured Output pattern from course.
     */
    public record KycRejectionExplanation(
            String userMessage,
            List<String> actionItems
    ) {}
}