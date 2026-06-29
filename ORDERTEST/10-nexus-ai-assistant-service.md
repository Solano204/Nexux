# 10 — nexus-ai-assistant-service
**Port:** 8090 | **Gateway base:** http://localhost:8080

## External Dependencies
- OpenAI API — PRIMARY (gpt-4o or configured model)
- Ollama (optional) — local model fallback
- PostgreSQL (nexus_ai_assistant DB on port 5433) — pgvector semantic memory
- Redis (port 6380) — conversation session cache

## Event Flow
No Kafka events — pure HTTP service. All endpoints respond via SSE (Server-Sent Events).

## Prerequisites
- Logged in (have `{accessToken}`)
- OPENAI_API_KEY must be set in .env — all endpoints return 500 without it

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8090/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

---

### USER-FACING ENDPOINTS (all SSE streaming)

```
Authorization: Bearer {accessToken}
```

### 2. Chat with AI assistant (SSE)
```
POST http://localhost:8080/api/v1/ai/chat
Authorization: Bearer {accessToken}
Content-Type: application/json
Accept: text/event-stream

{
  "message": "What is my account balance?",
  "sessionId": "session-test-001"
}
```
Expected: text/event-stream — tokens arriving one by one

> **Kafka topics:** none
> **DB affected:**
> - Redis `conversation:{sessionId}` — LRANGE (load last N turns as prompt context)
> - PostgreSQL `nexus_ai_assistant.conversation_embeddings` — vector similarity search (pgvector) — SELECT top-K relevant memory chunks
> - OpenAI API — POST /v1/chat/completions (model: gpt-4o, stream: true)
> - PostgreSQL `nexus_ai_assistant.conversation_embeddings` — INSERT embedding of this new turn (for future RAG)
> - Redis `conversation:{sessionId}` — LPUSH new turn + LTRIM to keep last 20

### 3. Chat follow-up (same sessionId = same context)
```
POST http://localhost:8080/api/v1/ai/chat
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "message": "Can you summarize my spending from last week?",
  "sessionId": "session-test-001"
}
```
Expected: text/event-stream — context-aware response

> **Kafka topics:** none
> **DB affected:** same as endpoint #2 — Redis (read history), pgvector (RAG search), OpenAI (stream), pgvector (INSERT new turn), Redis (update history)
> The model sees prior turns from Redis so it knows the conversation context.

### 4. Analyze document via chat endpoint (multipart SSE)
```
POST http://localhost:8080/api/v1/ai/chat/analyze-document
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data
Accept: text/event-stream

file: [image of a bill or receipt]
message: What is the total amount and due date on this bill?
sessionId: doc-session-001
```
Expected: text/event-stream — AI reads the document and answers

> **Kafka topics:** none
> **DB affected:**
> - Redis `conversation:{sessionId}` — LRANGE (load history)
> - OpenAI API — POST /v1/chat/completions with vision model (image base64 embedded in message), stream: true
> - PostgreSQL `nexus_ai_assistant.conversation_embeddings` — INSERT embedding of this document analysis
> - Redis `conversation:{sessionId}` — LPUSH + LTRIM

### 5. Analyze document via dedicated endpoint
```
POST http://localhost:8080/api/v1/ai/documents/analyze
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data
Accept: text/event-stream

file: [image of a document]
message: Extract all line items from this receipt
sessionId: doc-session-002
```
Expected: text/event-stream — structured extraction from document image

> **Kafka topics:** none
> **DB affected:**
> - Redis `conversation:{sessionId}` — LRANGE (load history)
> - OpenAI API — POST /v1/chat/completions (gpt-4o vision, stream: true)
> - PostgreSQL `nexus_ai_assistant.conversation_embeddings` — INSERT embedding
> - Redis `conversation:{sessionId}` — LPUSH + LTRIM
