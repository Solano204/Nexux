# AI CHAT ANALYZE DOCUMENT Flow — Docker + AWS

## Endpoint
```
POST /api/v1/ai/chat/analyze-document
```
Authenticated. Multipart upload. Returns SSE stream (text/event-stream).

## Relationship with /ai/documents/analyze
Both endpoints call the SAME service: `DocumentAnalysisService.analyzeAndRespond()`.
The difference is the controller:
- `/ai/chat/analyze-document` → `AiAssistantController` (scoped under `/ai/chat`)
- `/ai/documents/analyze` → `DocumentAnalysisController` (scoped under `/ai/documents`)

The logic is identical. See `18_AI_DOCUMENTS_ANALYZE_FLOW.md` for the deep flow.

## Entry Point
```
App → POST /api/v1/ai/chat/analyze-document → localhost:8080
Headers:
  Authorization: Bearer <jwt>
  Content-Type: multipart/form-data
  Accept: text/event-stream

Form parts:
  file:      <image file — jpg/png/webp/pdf>
  message:   "¿Cuánto tengo que pagar?"
  sessionId: "optional-session-id"
```

---

## Full Flow

```
App
 │
 │ POST /api/v1/ai/chat/analyze-document
 │ multipart: file + message + sessionId
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
│  AiAssistantController                 │
│  .analyzeDocument()                    │
│                                        │
│  userId = X-User-Id header             │
│  convId = userId + ":" + sessionId     │
│           (auto-UUID if no sessionId)  │
│                                        │
│  documentAnalysisService               │
│    .analyzeAndRespond(                 │
│       ByteArrayResource(file.bytes),   │
│       file.getContentType(),           │
│       message,                         │
│       convId)                          │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  DocumentAnalysisService               │
│                                        │
│  STEP 1 — Vision extraction            │
│  visionClient (gpt-4o-mini)            │
│  .prompt().system("Extract JSON...")   │
│  .user(text + media attachment)        │
│  .call().entity(DocumentExtractResult) │
│                                        │
│  Result schema:                        │
│  {                                     │
│    documentType: BILL|RECEIPT|...,     │
│    merchant: "CFE",                    │
│    totalAmount: 450.00,                │
│    currency: "MXN",                    │
│    dueDate: "2026-07-10",              │
│    accountNumber: "123456",            │
│    confidence: 0.92                    │
│  }                                     │
│                                        │
│  STEP 2 — Confidence gate              │
│  IF confidence < 0.7:                  │
│    stream: "I had difficulty reading   │
│             this document. Try a       │
│             clearer photo."            │
│    return                              │
│                                        │
│  STEP 2.5 — Async indexing             │
│  Thread.startVirtualThread(() ->       │
│    vectorStore.add(document))          │
│  Fire-and-forget, does NOT block stream│
│                                        │
│  STEP 3 — Enrich prompt               │
│  "User uploaded a BILL document.       │
│   merchant=CFE, amount=450.00 MXN,     │
│   dueDate=2026-07-10                   │
│   User question: ¿Cuánto tengo que     │
│   pagar?"                              │
│                                        │
│  STEP 4 — Stream response              │
│  primaryClient.prompt()                │
│    .user(enriched)                     │
│    .advisors(conversationId)           │
│    .stream().content()                 │
└──────────────────┬─────────────────────┘
                   │ SSE stream
                   ▼
App receives:
data: "Tu recibo de CFE es de $450 MXN"
data: " con fecha límite el 10 de julio."
data: " ¿Quieres que lo pague ahora?"
...
data: [DONE]
```

---

## Use cases

| Document | User message | AI response |
|---|---|---|
| CFE bill photo | "¿Cuánto debo?" | Amount + due date + offer to pay |
| Oxxo receipt | "¿En qué gasté?" | Category: grocery, amount |
| Bank statement | "¿Cuánto entró en mayo?" | Parses and sums income entries |
| Utility bill | "Paga esto" | Confirms details → calls transfer_funds tool |

---

## Supported file types

| MIME type | Support |
|---|---|
| image/jpeg | Full |
| image/png | Full |
| image/webp | Full |
| application/pdf | Partial (single page) |
