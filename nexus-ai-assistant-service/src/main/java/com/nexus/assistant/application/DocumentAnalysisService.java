package com.nexus.assistant.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor; // ← M6 key location
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

/**
 * Document Analysis Service — Multimodal (Section 8).
 *
 * Flow:
 * 1. Vision model (gpt-4o-mini) extracts financial data from image/PDF
 * 2. .call().entity() deserialises JSON → DocumentExtractResult record
 * 3. Validate confidence threshold (> 0.7)
 * 4. Enrich message with extracted data
 * 5. Stream response via primary client, binding conversationId
 *
 * M6 note:
 * ─ ChatMemory.CONVERSATION_ID does NOT exist in M6.
 *   The correct key in M6 is:
 *   AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY
 *
 * Use cases:
 * ─ Bill photos  → extract amount + due date → offer to pay
 * ─ Receipt photos → categorise spending
 * ─ Statement photos → answer questions about the document
 */
@Slf4j
@Service
public class DocumentAnalysisService {

    private final ChatClient visionClient;
    private final ChatClient primaryClient;

    public DocumentAnalysisService(
            @Qualifier("aiAssistantVisionClient")
            ChatClient visionClient,
            @Qualifier("aiAssistantPrimaryClient")
            ChatClient primaryClient) {
        this.visionClient = visionClient;
        this.primaryClient = primaryClient;
    }

    public Flux<String> analyzeAndRespond(
            org.springframework.core.io.Resource document,
            String mimeType,
            String userMessage,
            String conversationId) {

        return Flux.create(sink -> {
            try {
                // ── Step 1: Extract financial data with vision model ──
                //
                // .call().entity(Class) instructs Spring AI to parse the
                // model's JSON response into the record automatically.
                // No need to set responseFormat — entity() adds the
                // JSON-mode instruction to the prompt internally in M6.
                DocumentExtractResult extracted = visionClient.prompt()
                        .system("""
                                You are a financial document analyzer.
                                Extract all financial information from this document.
                                Return ONLY valid JSON matching exactly this shape:
                                {
                                  "documentType": "BILL|RECEIPT|STATEMENT|UNKNOWN",
                                  "merchant":     "<string or null>",
                                  "totalAmount":  <number or null>,
                                  "currency":     "<MXN|USD|EUR|etc>",
                                  "dueDate":      "<YYYY-MM-DD or null>",
                                  "accountNumber":"<string or null>",
                                  "confidence":   <0.0–1.0>
                                }
                                No extra keys, no markdown fences, no explanation.
                                """)
                        .user(u -> {
                            u.text("Extract financial data: " + userMessage);
                            u.media(MimeType.valueOf(mimeType), document);
                        })
                        .call()
                        .entity(DocumentExtractResult.class);

                // ── Step 2: Validate confidence ───────────────────────
                if (extracted == null || extracted.confidence() < 0.7) {
                    sink.next("I had difficulty reading this document. " +
                            "Could you try a clearer photo or enter the " +
                            "details manually?");
                    sink.complete();
                    return;
                }

                // ── Step 3: Enrich prompt with extracted data ─────────
                String enriched = """
                        User uploaded a %s document.
                        Extracted: merchant=%s, amount=%s %s, dueDate=%s
                        User question: %s

                        Help the user. If it's a bill they want to pay,
                        confirm details before calling transfer_funds.
                        """.formatted(
                        extracted.documentType(),
                        extracted.merchant(),
                        extracted.totalAmount(),
                        extracted.currency(),
                        extracted.dueDate() != null
                                ? extracted.dueDate() : "not specified",
                        userMessage);

                // ── Step 4: Stream response via primary client ────────
                //
                // M6: conversation ID is passed via the advisor param key
                // AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                // NOT via ChatMemory.CONVERSATION_ID (that constant is M7+).
                primaryClient.prompt()
                        .user(enriched)
                        .advisors(a -> a.param(
                                AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                                conversationId))
                        .stream()
                        .content()
                        .subscribe(
                                sink::next,
                                sink::error,
                                sink::complete
                        );

            } catch (Exception e) {
                log.error("Document analysis failed: {}", e.getMessage(), e);
                sink.next("Sorry, I couldn't analyze that document. " +
                        "Please try again with a clearer image.");
                sink.complete();
            }
        });
    }

    /**
     * Structured result returned by the vision model.
     * Spring AI's .entity() deserialises the JSON response into this record.
     */
    public record DocumentExtractResult(
            String documentType,
            String merchant,
            Double totalAmount,
            String currency,
            String dueDate,
            String accountNumber,
            double confidence
    ) {}
}