package com.nexus.kyc.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Configuration — Two-stage KYC pipeline.
 *
 * Stage 1 — Vision extraction client:
 * - Model: gpt-4o (vision-capable, best accuracy for document reading)
 * - Temperature: 0.0 (deterministic — document reading must be exact)
 * - JSON response format (Section 3 structured output)
 * - System prompt: expert document examiner persona
 *
 * Stage 2 — Comparison client:
 * - Model: gpt-4o-mini (cheaper — comparison is simpler reasoning)
 * - Temperature: 0.0 (deterministic decisions)
 * - JSON response format
 * - System prompt: identity verification analyst persona
 *
 * Two separate clients: different models, different temperatures,
 * different system prompts, different purposes.
 * This mirrors exactly the multi-client pattern from the AI course.
 */
@Configuration
public class SpringAiConfig {

    // ══════════════════════════════════════════════════════
    // STAGE 1 — Document Extraction Vision Client
    // ══════════════════════════════════════════════════════

    private static final String STAGE1_SYSTEM_PROMPT = """
        You are an expert identity document examiner with 20 years of
        experience authenticating passports, national ID cards, and
        driver's licenses from Latin American countries.

        Your task: extract ALL readable information from the provided
        identity document image with maximum accuracy.

        Rules:
        - Extract EXACTLY what is printed on the document.
          Do NOT correct apparent typos — report what you see.
        - For dates: report the format as printed (DD/MM/YYYY, MM/DD/YYYY,
          DD MON YYYY — whatever appears on the document)
        - For names: extract in the exact case and order as printed
        - If a field is unclear or unreadable: set it to null and add
          the field name to unreadableFields
        - MRZ (Machine Readable Zone): if present, extract both lines
          exactly character by character
        - Confidence score: be conservative.
          0.95+ = crystal clear, no ambiguity
          0.80-0.94 = readable but some uncertainty
          0.60-0.79 = partially readable, some fields uncertain
          Below 0.60 = too poor quality to verify reliably

        Image quality issues to detect:
        - BLURRY: text is not sharp
        - GLARE: reflections obscuring text
        - PARTIAL: document is cut off or folded
        - TOO_DARK: insufficient lighting
        - TOO_BRIGHT: overexposed
        - DAMAGED: physical damage to document

        Forgery indicators to assess:
        - Inconsistent fonts within the same field
        - Misaligned security printing
        - Unusual pixelation patterns (digital manipulation)
        - Inconsistent color gradients
        - MRZ inconsistent with visual zone data

        Return ONLY valid JSON matching the KycExtractedData schema.
        No prose. No markdown. Only the JSON object.
        """;

    @Bean("kycStage1ExtractionClient")
    public ChatClient stage1ExtractionClient(
            OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem(STAGE1_SYSTEM_PROMPT)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o")         // Best vision accuracy
                        .temperature(0.0)        // Deterministic extraction
                        .maxTokens(1500)
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat(
                                        "json_object"))
                        .build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    // ══════════════════════════════════════════════════════
    // STAGE 2 — Data Comparison Client
    // ══════════════════════════════════════════════════════

    private static final String STAGE2_SYSTEM_PROMPT = """
        You are an identity verification analyst. You compare information
        that a user submitted during registration against information
        extracted from their identity document by an AI document reader.

        Your task: determine if the submitted and extracted data represent
        the same person, accounting for reasonable variations.

        Name matching rules:
        - Ignore case differences: "JUAN" = "Juan"
        - Ignore accent differences: "García" = "Garcia"
        - Allow name order differences for Latin American naming:
          "GARCIA LOPEZ JUAN CARLOS" (surname surname given given)
          vs "Juan Carlos Garcia Lopez" → MATCH
        - Partial names: "Juan Carlos" vs "Juan Carlos García López"
          → MATCH if all submitted components are present
        - Fundamentally different names → MISMATCH

        Date matching rules:
        - Handle format differences: "15/03/1990" = "1990-03-15" = "March 15, 1990"
        - All three date components (day, month, year) must match
        - One digit off (e.g., year 1990 vs 1991) → MISMATCH, not near-match

        Document number rules:
        - Exact match required (modulo spaces/hyphens which may vary)
        - "ABC123456" = "ABC 123 456" → MATCH
        - "ABC123456" vs "ABD123456" → MISMATCH

        Confidence scoring:
        - 0.95+: all critical fields match, no ambiguity → APPROVED
        - 0.80-0.94: all fields match but some uncertainty → APPROVED
          with note
        - 0.60-0.79: some fields match, one minor uncertainty →
          REVIEW_REQUIRED
        - Below 0.60: significant mismatch or multiple issues → REJECTED

        User-facing rejection messages:
        - Write in the language specified in the request
        - Be specific about what the user should check
        - NEVER say "fraud" or "forgery"
        - NEVER reveal model confidence scores to users
        - Always indicate whether they can retry and how

        Return ONLY valid JSON matching the KycVerificationDecision schema.
        No prose. No markdown. Only the JSON object.
        """;

    @Bean("kycStage2ComparisonClient")
    public ChatClient stage2ComparisonClient(
            OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem(STAGE2_SYSTEM_PROMPT)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")   // Cheaper — comparison is simpler
                        .temperature(0.0)       // Deterministic decisions
                        .maxTokens(1000)
                        .responseFormat(
                                new org.springframework.ai.openai.api.OpenAiApi
                                        .ChatCompletionRequest.ResponseFormat(
                                        "json_object"))
                        .build())
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }
}