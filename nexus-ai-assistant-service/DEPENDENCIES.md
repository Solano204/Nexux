# nexus-ai-assistant-service — Complete Dependency & Run Guide

## ⚠️ READ BUGS.md FIRST

Fix the broken `Main.java` before attempting to start the service.

---

## 1. Three things created from scratch

| Item | Status |
|------|--------|
| `Dockerfile` | **NEW** — was completely absent |
| `logback-spring.xml` | **REPLACED** — original was incomplete (no Loki, no ASYNC wrapper, no MDC fields) |
| `.github/workflows/nexus-ai-assistant-service.yml` | **NEW** — no CI existed |

---

## 2. What you need installed

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | **25** | `--enable-preview`, virtual threads |
| Maven | 3.9+ | Build tool |
| Docker Desktop | 24+ with Compose v2 | All infrastructure |
| ~4GB free disk | — | Ollama mistral:7b model |
| ~8GB RAM | — | mistral:7b inference (when used as fallback) |

---

## 3. Runtime dependencies

### 3.1 Always required at startup

| Service | Port | Why |
|---------|------|-----|
| **PostgreSQL 16 + pgvector** | 5432 | JDBC chat memory schema + pgvector RAG embeddings |
| **Redis 7** | 6379 | Session state per conversation (userId + conversationId) |
| **Kafka** | 9092 | `AiQueryEventProducer` — publishes AI interaction events |
| **Ollama** | 11434 | Fallback LLM — service starts but first request fails if model not pulled |
| **nexus-config-service** | 8888 | `fail-fast: true` |
| **nexus-discovery-service** | 8761 | Service registration |

### 3.2 Required for tool calling (AI capabilities)

| Service | Port | Tool that calls it |
|---------|------|-------------------|
| **nexus-account-service** | 8085 | `AccountBalanceTool`, `SavingsRecommendationsTool` |
| **nexus-transaction-service** | 8086 | `TransactionHistoryTool`, `SpendingAnalysisTool`, `TransferFundsTool` |
| **nexus-fraud-service** | 8087 | `FraudAlertsTool` |

Without these services, the corresponding tools return errors. The AI assistant gracefully handles tool failures and informs the user.

### 3.3 Optional

| Service | What breaks without it |
|---------|------------------------|
| **OpenAI API** | Falls back to Ollama/mistral:7b for all requests |
| **nexus-zipkin** | Traces not visible |
| **nexus-loki** | Logs not in Grafana |

---

## 4. ⚠️ ONE-TIME: Pull Ollama model

After running `docker compose up -d` for the first time, pull the mistral:7b model:

```bash
chmod +x scripts/pull-ollama-model.sh
./scripts/pull-ollama-model.sh
```

This is a ~4GB download and only needs to be done once — the model persists in the `ollama-models` Docker volume.

Without the model, Ollama is running but has nothing to serve. The service starts fine, but fallback requests return an error until the model is available.

---

## 5. Missing dependency — add to pom.xml

```xml
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>1.5.2</version>
</dependency>
```

---

## 6. How the AI architecture works

### Multi-provider setup
- **Primary:** OpenAI GPT-4o-mini (qualityResponse, function calling)
- **Agent:** OpenAI GPT-4o-mini with Plan-then-Act ReAct loop (max 8 steps)
- **Fallback:** Ollama mistral:7b (local, free, lower quality)
- Circuit breaker: if primary fails, `fallbackClient` (Ollama) is used automatically

### Memory
- **Window:** `MessageChatMemoryAdvisor` with `InMemoryChatMemory` (last N messages, keyed by conversationId)
- **Semantic:** `VectorStoreChatMemoryAdvisor` searching pgvector for relevant past context

### Tool Calling (Section 11)
6 tools registered: `AccountBalanceTool`, `TransactionHistoryTool`, `SpendingAnalysisTool`, `TransferFundsTool`, `FraudAlertsTool`, `SavingsRecommendationsTool`

### RAG (Section 10)
Financial knowledge base in pgvector. Multi-query expansion (4 paraphrases), cosine similarity, top-K retrieval.

### Streaming (Section 3)
`AiAssistantController` returns `Flux<String>` as `text/event-stream` (SSE). Gateway has a 120s timeout for this route.

---

## 7. Environment variables

| Variable | Default | Required? |
|----------|---------|-----------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** |
| `DATASOURCE_URL` | `jdbc:postgresql://nexus-postgres:5432/nexus_ai_assistant` | no |
| `POSTGRES_USER` | `nexus` | no |
| `POSTGRES_PASSWORD` | `""` | prod: **YES** |
| `REDIS_HOST` | `nexus-redis` | no |
| `REDIS_PASSWORD` | `""` | prod: **YES** |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no |
| `OPENAI_API_KEY` | `""` | no — Ollama fallback used if empty |
| `OLLAMA_BASE_URL` | `http://nexus-ollama:11434` | no |
| `ACCOUNT_SERVICE_URL` | `http://nexus-account-service:8085` | no |
| `TRANSACTION_SERVICE_URL` | `http://nexus-transaction-service:8086` | no |
| `FRAUD_SERVICE_URL` | `http://nexus-fraud-service:8087` | no |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no |
| `TRACING_SAMPLE_RATE` | `1.0` | no — use `0.05` for prod (AI calls expensive) |

---

## 8. How to run

### 8a. Full Docker Compose

```bash
# 1. Fix Main.java (see BUGS.md)
# 2. Build
mvn package -DskipTests
# 3. Start everything
docker compose up -d
# 4. Pull Ollama model (one-time)
./scripts/pull-ollama-model.sh
# 5. Verify
curl http://localhost:8090/actuator/health
```

### 8b. Test streaming SSE

```bash
curl -N -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -X POST http://localhost:8090/api/v1/ai/chat \
  -d '{"message": "What is my account balance?", "conversationId": "test-123"}'
```

---

## 9. API Gateway integration

The gateway routes `/ai/**` to this service with:
- `timeout-duration: 120s` (SSE streams can run for 2 minutes)
- `cancel-running-future: false` (don't cancel SSE mid-stream)
- Rate limiter: 1 req/s replenish, burst 5 (AI is expensive)
- `StripPrefix=1` (removes `/ai` prefix before forwarding)

---

## 10. Common problems

### "mistral:7b not found" error in Ollama
Model not yet pulled. Run `./scripts/pull-ollama-model.sh`.

### SSE connection closes immediately
Check that the client is setting `Accept: text/event-stream` and the gateway timeout allows enough time.

### "pgvector extension not found"
Using plain `postgres:16` image. The `docker-compose.yml` uses `pgvector/pgvector:pg16`. The `scripts/postgres-init.sql` installs the extension, but it must be available in the image first.

### Spring AI JDBC chat memory schema missing
On fresh start with `initialize-schema: always`, Spring AI creates the table automatically. If it fails (permissions issue), check that the `nexus` user has `CREATE TABLE` rights on the `nexus_ai_assistant` database.
