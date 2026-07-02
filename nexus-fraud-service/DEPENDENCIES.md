# nexus-fraud-service — Complete Dependency & Run Guide

## ⚠️ READ BUGS.md FIRST

Two bugs that prevent startup. Fix them before anything else.

---

## 1. Three things created from scratch

| Item | Status |
|------|--------|
| `Dockerfile` | **NEW** — was completely absent |
| `logback-spring.xml` | **IMPROVED** — original had console output only; added Loki appender, ASYNC_LOKI (neverBlock), fraud-specific MDC fields, production profile |
| `.github/workflows/nexus-fraud-service.yml` | **NEW** — no CI existed |

---

## 2. What you need installed

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | **25** | `--enable-preview`, virtual threads |
| Maven | 3.9+ | Build tool |
| Docker Desktop | 24+ with Compose v2 | All infrastructure |

---

## 3. Runtime dependencies

### 3.1 Always required at startup

| Service | Port | Why |
|---------|------|-----|
| **PostgreSQL 16 + pgvector** | 5432 | `fraud_decisions` + `outbox` + `fraud_policy_embeddings` (RAG) |
| **Redis 7** | 6379 | Velocity cache (5-min, 1-hour windows) + fraud score cache |
| **Elasticsearch 8.13** | 9200 | Transaction history lookup (`VelocityCheckTool`, `GeolocationAnomalyTool`) |
| **Kafka** | 9092 | `saga.commands` consumer + fraud results producer |
| **nexus-config-service** | 8888 | `fail-fast: true` |
| **nexus-discovery-service** | 8761 | Service registration |

### 3.2 Required for full functionality

| Service | What breaks without it |
|---------|------------------------|
| **OpenAI API** | ReAct agent cannot run — no fallback model. ALL fraud analysis requests fail. SAGA transactions stuck in `FRAUD_CHECKING` state. |
| **nexus-zipkin** | Traces not visible |
| **nexus-loki** | Logs not in Grafana |

**Important:** Unlike other AI services, this one has **no fallback LLM**. If OpenAI is unavailable, the Resilience4j circuit breaker opens and all fraud checks are rejected, blocking the entire transaction flow. Set `OPENAI_API_KEY` before starting.

---

## 4. SAGA integration

The fraud service is a **critical SAGA participant**. When `nexus-transaction-service` initiates a transaction:

1. It sends `CHECK_FRAUD` to `saga.commands` topic
2. This service consumes it, runs the ReAct agent (up to 8 steps)
3. Publishes the `FraudDecision` to `fraud.results` topic
4. Transaction service awaits the result (timeout: 30s)

If this service is down or slow, transactions remain stuck in `FRAUD_CHECKING` state. The transaction service eventually times out and rolls back.

---

## 5. ReAct Agent tools

The `FraudReActAgent` uses these 6 tools in sequence for each transaction:

| Tool | What it does |
|------|-------------|
| `VelocityCheckTool` | Queries Redis + Elasticsearch for 5-min and 1-hour transaction velocity |
| `RagPolicyTool` | pgvector similarity search for relevant fraud policies from knowledge base |
| `AccountRelationshipTool` | Checks if merchant/payee has been used by this account before |
| `GeolocationAnomalyTool` | Detects impossible travel (location changed faster than physically possible) |
| `MerchantRiskTool` | Checks merchant blacklist and risk category |
| `BehavioralAnalysisTool` | Compares current transaction against historical spending patterns |

Rules enforced in the system prompt:
- `velocity_check_tool` MUST be called on every transaction
- `rag_policy_tool` MUST be called on every transaction
- Result is a structured `FraudDecision` JSON with `riskScore` (0-100) and `outcome` (APPROVE/REVIEW/REJECT)

---

## 6. Missing dependency — add to pom.xml

```xml
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>1.5.2</version>
</dependency>
```

---

## 7. Environment variables

| Variable | Default | Required? |
|----------|---------|-----------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** |
| `DATASOURCE_URL` | `jdbc:postgresql://nexus-postgres:5432/nexus_fraud` | no |
| `POSTGRES_USER` | `nexus` | no |
| `POSTGRES_PASSWORD` | `""` | prod: **YES** |
| `REDIS_HOST` | `nexus-redis` | no |
| `REDIS_PASSWORD` | `""` | prod: **YES** |
| `ELASTICSEARCH_URI` | `http://nexus-elasticsearch:9200` | no |
| `ELASTICSEARCH_PASSWORD` | — | prod: **YES** |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no |
| `OPENAI_API_KEY` | `""` | **YES** — no fallback; all analysis fails without it |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no |
| `TRACING_SAMPLE_RATE` | `1.0` | no — use `0.1` for prod |

---

## 8. How to run

### After fixing bugs in BUGS.md:

```bash
mvn package -DskipTests
docker compose up -d
docker compose logs -f nexus-fraud-service
curl http://localhost:8087/actuator/health
```

---

## 9. Flyway migrations

| File | Creates |
|------|---------|
| `V1__create_fraud_decisions.sql` | `fraud_decisions` table |
| `V2__create_outbox.sql` | `outbox` — Debezium CDC |

After fixing Bug 2 (directory rename), verify:
```bash
docker exec nexus-postgres psql -U nexus -d nexus_fraud \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

---

## 10. Common problems

### SAGA transactions stuck in FRAUD_CHECKING
Either the fraud service is down, or `OPENAI_API_KEY` is missing/invalid. Check:
```bash
curl http://localhost:8087/actuator/health
# Also check circuit breaker state:
curl http://localhost:8087/actuator/health | jq '.components.circuitBreakers'
```

### pgvector extension missing
```bash
docker exec nexus-postgres psql -U nexus -d nexus_fraud \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
docker compose restart nexus-fraud-service
```

### Elasticsearch `vm.max_map_count` too low (Linux only)
```bash
sudo sysctl -w vm.max_map_count=262144
```
