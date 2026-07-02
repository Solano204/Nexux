package com.nexus.assistant.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 *   "chat_memory_conversation_id"
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
    private final VectorStore financialKnowledgeVectorStore;

    public DocumentAnalysisService(
            @Qualifier("aiAssistantVisionClient")
            ChatClient visionClient,
            @Qualifier("aiAssistantPrimaryClient")
            ChatClient primaryClient,
            @Qualifier("financialKnowledgeVectorStore")
            VectorStore financialKnowledgeVectorStore) {
        this.visionClient = visionClient;
        this.primaryClient = primaryClient;
        this.financialKnowledgeVectorStore = financialKnowledgeVectorStore;
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

                // ── Step 2.5: Index document into financial_knowledge_base ──
                // Fire-and-forget — indexing never blocks the streaming response
                final DocumentExtractResult finalExtracted = extracted;
                final String finalUserMessage = userMessage;
                final String finalConversationId = conversationId;
                Thread.startVirtualThread(() ->
                        indexDocumentIntoKnowledgeBase(
                                finalExtracted, finalUserMessage, finalConversationId));

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
                // "chat_memory_conversation_id",
                // NOT via ChatMemory.CONVERSATION_ID (that constant is M7+).
                primaryClient.prompt()
                        .user(enriched)
                        .advisors(a -> a.param(
                                "chat_memory_conversation_id",
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

    private void indexDocumentIntoKnowledgeBase(DocumentExtractResult extracted,
                                                 String userMessage,
                                                 String conversationId) {
        try {
            String content = String.format(
                    "[%s] Document analyzed — type: %s, merchant: %s, " +
                    "amount: %s %s, due date: %s. User context: %s",
                    Instant.now(),
                    extracted.documentType(),
                    extracted.merchant() != null ? extracted.merchant() : "unknown",
                    extracted.totalAmount() != null
                            ? extracted.totalAmount().toString() : "N/A",
                    extracted.currency() != null ? extracted.currency() : "",
                    extracted.dueDate() != null ? extracted.dueDate() : "N/A",
                    userMessage);

            String docId = UUID.nameUUIDFromBytes(
                    (conversationId + "-" + Instant.now().toEpochMilli())
                            .getBytes()).toString();

            Document doc = Document.builder()
                    .id(docId)
                    .text(content)
                    .metadata("source", "document_analysis")
                    .metadata("document_type", extracted.documentType() != null
                            ? extracted.documentType() : "UNKNOWN")
                    .metadata("merchant", extracted.merchant() != null
                            ? extracted.merchant() : "")
                    .metadata("currency", extracted.currency() != null
                            ? extracted.currency() : "MXN")
                    .metadata("conversation_id", conversationId)
                    .metadata("indexed_at", Instant.now().toString())
                    .build();

            financialKnowledgeVectorStore.add(List.of(doc));
            log.debug("Indexed analyzed document into financial_knowledge_base: " +
                    "conversationId={} type={}", conversationId, extracted.documentType());

        } catch (Exception e) {
            log.warn("Failed to index document into financial_knowledge_base: {}",
                    e.getMessage());
        }
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