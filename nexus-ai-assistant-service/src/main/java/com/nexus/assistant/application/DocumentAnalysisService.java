package com.nexus.assistant.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

/**
 * Document Analysis Service — Multimodal (Section 8).
 *
 * Flow:
 * 1. Vision model (gpt-4o-mini) extracts financial data
 * 2. Parse JSON into DocumentExtractResult
 * 3. Validate confidence threshold (> 0.7)
 * 4. Enrich message with extracted data
 * 5. Stream response via primary client
 *
 * Use cases:
 * - Bill photos → extract amount + due date → offer to pay
 * - Receipt photos → categorize spending
 * - Statement photos → answer questions about the document
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
                // Step 1: Extract financial data with vision model
                DocumentExtractResult extracted = visionClient.prompt()
                        .system("""
                        You are a financial document analyzer.
                        Extract all financial information from this document.
                        Return ONLY JSON: {
                          "documentType": "BILL|RECEIPT|STATEMENT|UNKNOWN",
                          "merchant": "string or null",
                          "totalAmount": number or null,
                          "currency": "MXN|USD|etc",
                          "dueDate": "YYYY-MM-DD or null",
                          "accountNumber": "string or null",
                          "confidence": 0.0-1.0
                        }
                        """)
                        .user(u -> {
                            u.text("Extract financial data: " + userMessage);
                            u.media(MimeType.valueOf(mimeType), document);
                        })
                        .call()
                        .entity(DocumentExtractResult.class);

                // Step 2: Validate confidence
                if (extracted == null ||
                        extracted.confidence() < 0.7) {
                    sink.next("I had difficulty reading this document. " +
                            "Could you try a clearer photo or enter the " +
                            "details manually?");
                    sink.complete();
                    return;
                }

                // Step 3: Enrich message with extracted data
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

                // Step 4: Stream response
                primaryClient.prompt()
                        .user(enriched)
                        .advisors(a -> a.param(
                                ChatMemory.CONVERSATION_ID, conversationId))
                        .stream()
                        .content()
                        .subscribe(
                                token -> sink.next(token),
                                sink::error,
                                sink::complete
                        );

            } catch (Exception e) {
                log.error("Document analysis failed: {}",
                        e.getMessage(), e);
                sink.next("Sorry, I couldn't analyze that document. " +
                        "Please try again with a clearer image.");
                sink.complete();
            }
        });
    }

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