# AI DOCUMENTS ANALYZE Flow — Docker + AWS

## Endpoint
```
POST /api/v1/ai/documents/analyze
```
Authenticated. Multipart upload. Returns SSE stream (text/event-stream).

## Relationship with /ai/chat/analyze-document
Both endpoints call the SAME `DocumentAnalysisService.analyzeAndRespond()`.
This controller (`DocumentAnalysisController`) is scoped under `/ai/documents`.
The other (`AiAssistantController`) is scoped under `/ai/chat`.
Logic is identical — prefer this one for document-first flows, the other for chat-first flows.

## Entry Point
```
App → POST /api/v1/ai/documents/analyze → localhost:8080
Headers:
  Authorization: Bearer <jwt>
  Content-Type: multipart/form-data
  Accept: text/event-stream

Form parts:
  file:      <image — jpg/png/webp>
  message:   "What is this document about?"
  sessionId: "optional-session-id"
```

---

## Full Flow (step by step)

```
App
 │
 │ POST /api/v1/ai/documents/analyze
 │ multipart: file + message [+ sessionId]
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
│  JWT validate → X-User-Id header       │
│  Route → nexus-ai-assistant-service    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-ai-assistant-service :8090      │
│  DocumentAnalysisController            │
│                                        │
│  userId = X-User-Id header             │
│  convId = userId + ":" + sessionId     │
│           (auto-UUID if not provided)  │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
STEP 1 — Vision extraction (blocking)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  visionClient → OpenAI gpt-4o-mini     │
│                                        │
│  System prompt:                        │
│  "You are a financial document         │
│   analyzer. Extract all financial      │
│   information. Return ONLY valid JSON" │
│                                        │
│  User content:                         │
│  - text: "Extract financial data: ..."│
│  - media: file attachment (image)      │
│                                        │
│  Response parsed via                   │
│  .call().entity(DocumentExtractResult) │
│                                        │
│  Result:                               │
│  {                                     │
│    documentType: "BILL",               │
│    merchant: "Telmex",                 │
│    totalAmount: 299.00,                │
│    currency: "MXN",                    │
│    dueDate: "2026-07-05",              │
│    accountNumber: "555-888-0001",      │
│    confidence: 0.88                    │
│  }                                     │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
STEP 2 — Confidence gate
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  IF confidence < 0.70:                 │
│    stream short error message          │
│    "I had difficulty reading this..."  │
│    complete (no further processing)    │
│                                        │
│  IF confidence >= 0.70:                │
│    Continue to Step 2.5               │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
STEP 2.5 — Async vectorstore indexing
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  Thread.startVirtualThread(() ->       │
│    indexDocumentIntoKnowledgeBase())   │
│                                        │
│  DOES NOT block SSE stream response    │
│                                        │
│  Creates Document object:             │
│  content: "[timestamp] BILL analyzed  │
│    merchant: Telmex, amount: 299 MXN  │
│    dueDate: 2026-07-05..."             │
│                                        │
│  Metadata: source, document_type,     │
│    merchant, currency, conversation_id│
│                                        │
│  financialKnowledgeVectorStore.add()  │
│  → pgvector (financial_knowledge_base) │
│                                        │
│  Purpose: future RAG queries can find │
│  "bills analyzed for this user"        │
└────────────────────────────────────────┘
                   │ (async)
                   ▼
═══════════════════════════════════════════
STEP 3 — Prompt enrichment
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  Build enriched prompt:                │
│                                        │
│  "User uploaded a BILL document.       │
│   Extracted: merchant=Telmex,          │
│   amount=299.0 MXN,                    │
│   dueDate=2026-07-05                   │
│   User question: What is this about?   │
│                                        │
│   Help the user. If it's a bill they   │
│   want to pay, confirm details before  │
│   calling transfer_funds."             │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
STEP 4 — Stream response
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  primaryClient (OpenAI GPT-4o-mini)    │
│  .prompt().user(enrichedMessage)       │
│  .advisors(conversationId)             │
│    → MessageChatMemoryAdvisor          │
│  .stream().content()                   │
│                                        │
│  Tokens emitted as SSE                 │
└──────────────────┬─────────────────────┘
                   │ SSE stream
                   ▼
App receives real-time response:
data: "Este es tu recibo de Telmex"
data: " por $299 MXN, con vencimiento"
data: " el 5 de julio."
data: " ¿Te gustaría que lo pague ahora?"
data: [DONE]
```

---

## If user says "yes, pay it"

```
Follow-up: POST /api/v1/ai/documents/analyze
  (same sessionId, message: "sí, paga")
        │
        ▼
Step 4: primaryClient still has conversation context
  AI calls transfer_funds tool:
    → POST /api/v1/transactions
    → INTERNAL_TRANSFER or PAYMENT saga starts
  AI streams confirmation:
  "Pago de $299 a Telmex procesado. ✓"
```

---

## Two AI models used in one request

| Step | Model | Purpose |
|---|---|---|
| Step 1 | gpt-4o-mini (visionClient) | Extract structured JSON from image |
| Step 4 | gpt-4o-mini (primaryClient) | Interpret + respond to user |

Both are configured in `SpringAiConfig.java` via `OPENAI_API_KEY` in `.env`.
If `OPENAI_API_KEY` is a placeholder, both models fail and the service returns an error.
