# nexus-ai-assistant-service — Complete Dependency Map

## Port: 8090

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                                 |
|-------------------|-------|---------------------------------------------------------------------|
| Redis             | 6380  | conversation history cache per session                              |
| Kafka             | 19092 | produces ai-query-logged events for audit                           |
| OpenAI API        | HTTPS | primary AI model for chat + document analysis                       |
| Ollama            | 11434 | local LLM fallback (optional — used when OpenAI unavailable)        |
| Config service    | 8888  | loads config on startup                                             |
| Discovery service | 8761  | Eureka registration                                                 |

**NO PostgreSQL, NO MongoDB, NO Elasticsearch, NO S3, NO SQS**

---

## Kafka topics PRODUCED

| Topic           | Consumed by       | When                                   |
|-----------------|-------------------|----------------------------------------|
| ai-query-logged | audit-write-native | every AI query for compliance logging |

---

## Kafka topics CONSUMED

None — this service only produces.

---

## Public endpoints (require JWT — gateway path /ai/** with StripPrefix=1)

| Method | Gateway path         | Actual path                  | Body                                       |
|--------|----------------------|------------------------------|--------------------------------------------|
| POST   | /ai/api/v1/ai/chat   | /api/v1/ai/chat              | `{ "message": "...", "sessionId": "..." }` |
| POST   | /ai/api/v1/ai/stream | /api/v1/ai/stream            | same — returns SSE stream                  |
| POST   | /ai/api/v1/ai/documents/analyze | /api/v1/ai/documents/analyze | multipart document file        |

---

## Notes on gateway routing

Gateway dev config: path `/ai/**` with `StripPrefix=1` — so you call:
```
POST http://localhost:8080/ai/api/v1/ai/chat         → forwards to → /api/v1/ai/chat
POST http://localhost:8080/ai/api/v1/ai/stream       → forwards to → /api/v1/ai/stream
POST http://localhost:8080/ai/api/v1/ai/documents/analyze → forwards to → /api/v1/ai/documents/analyze
```

---

## Indirect dependencies

| Component     | Role                                                              |
|---------------|-------------------------------------------------------------------|
| PostgreSQL    | NOT used                                                          |
| MongoDB       | NOT used directly — audit-write writes ai-query-logged events     |
| Elasticsearch | NOT used                                                          |
| S3 / SQS      | NOT used                                                          |

---

## Notes

- Redis port in application.yml defaults to 6379 — config server dev file should override to 6380. Verify before testing.
- Without OpenAI key: chat returns error or fallback Ollama response
- Ollama must be running locally on port 11434 for fallback to work (optional)
- Rate limited at gateway: 1 req/s burst 5 (most restrictive in the platform)
- StripPrefix=1 means the /ai prefix is stripped before forwarding — don't hit /api/v1/ai directly through gateway
