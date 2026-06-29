# LEDGER EXPLAIN Flow — Docker + AWS

## Endpoint
```
POST /api/v1/ledger/accounts/{accountId}/explain
```
Authenticated. Returns SSE stream (text/event-stream).

## What it does
AI-powered explanation of ledger entries. User asks a question in natural language
("explain my last 5 transactions", "why was $15 charged?") and gets a streaming
plain-language response using Advanced RAG + live ledger tool calls.

## Entry Point
```
App → POST /api/v1/ledger/accounts/{accountId}/explain → localhost:8080
Headers:
  Authorization: Bearer <jwt>
  Accept: text/event-stream
Body: {
  "message": "Explícame mis últimos 5 movimientos",
  "sessionId": "optional-session-id"
}
```

---

## Full Flow

```
App
 │
 │ POST /api/v1/ledger/accounts/{id}/explain
 │ Accept: text/event-stream
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
│  JWT validate → X-User-Id header       │
│  Route → nexus-ledger-service :8088    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-ledger-service :8088            │
│  LedgerController.explainTransactions()│
│                                        │
│  1. extractUserId from X-User-Id       │
│  2. sessionId = request.sessionId()    │
│     OR auto: "explain-{acctId}-{userId}"
│  3. Enrich message with:               │
│     accountId (for tool calls)         │
│     today's date (for "this month")    │
│  4. Call LedgerExplainerService        │
│     .explainStreaming(accountId, msg)  │
│  5. Return Flux<String> as SSE stream  │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  LedgerExplainerService                │
│  Spring AI ChatClient (explainerClient)│
│                                        │
│  RAG pipeline:                         │
│  ┌─────────────────────────────────┐   │
│  │ QuestionAnswerAdvisor           │   │
│  │ Vector search → pgvector        │   │
│  │ Index: financial_literacy_kb    │   │
│  │ Retrieves relevant context:     │   │
│  │ "What is a debit entry?"        │   │
│  │ "How does double-entry work?"   │   │
│  └─────────────────────────────────┘   │
│                                        │
│  Tool calls (live data):               │
│  ┌─────────────────────────────────┐   │
│  │ LedgerExplainerTools            │   │
│  │ getRecentEntries(accountId, n)  │   │
│  │   → PostgreSQL / MongoDB query  │   │
│  │ getMonthlySummary(accountId,    │   │
│  │   year, month)                  │   │
│  │   → PostgreSQL ledger tables    │   │
│  └─────────────────────────────────┘   │
│                                        │
│  Memory:                               │
│  ┌─────────────────────────────────┐   │
│  │ MessageChatMemoryAdvisor        │   │
│  │ key: chat_memory_conversation_id│   │
│  │ = sessionId                     │   │
│  │ Stores: user Q + assistant A    │   │
│  │ Follow-up questions have context│   │
│  └─────────────────────────────────┘   │
│                                        │
│  LLM: OpenAI (configured in           │
│       SpringAiConfig.java)            │
│                                        │
│  .stream().content()                   │
│  Returns Flux<String> — tokens as     │
│  they are generated                    │
└──────────────────┬─────────────────────┘
                   │ SSE stream
                   ▼
App receives tokens in real time:
data: "Tus últimos 5 movimientos fueron:\n"
data: "1. **Transferencia** de $1,000 el 27 de junio..."
data: "2. **Comisión SPEI** de $15..."
...
data: [DONE]
```

---

## AI models used

| Step | Model | Mode |
|---|---|---|
| Explanation generation | OpenAI (configured in .env) | Streaming |
| RAG retrieval | pgvector cosine similarity | In-process |
| Fallback (no OpenAI) | Response fails | No local fallback |

---

## Data sources for tool calls

| Tool | Source | Latency |
|---|---|---|
| getRecentEntries | PostgreSQL postings table | ~10ms |
| getMonthlySummary | MongoDB AccountLedgerSummaryDocument | ~5ms |

---

## SSE response format

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache

data: Tu saldo actual es $12,500.

data: En junio realizaste 8 transacciones.

data: Los cargos incluyen: $350 Oxxo, $1,000 renta,...

data: [DONE]
```

---

## Session memory for follow-ups

```
Session 1 — "explain my last 5 transactions"
  → AI reads entries, explains them

Session 2 — "and which one was the most expensive?"
  → MessageChatMemoryAdvisor sends conversation history
  → AI knows context from Session 1 without re-asking
```

Memory is stored per sessionId. If sessionId is omitted, it auto-generates per user+account.
