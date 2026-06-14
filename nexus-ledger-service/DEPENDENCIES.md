# nexus-ledger-service — Complete Dependency & Run Guide

## ⚠️ READ BUGS.md FIRST

**The service will not start at all until you fix the bugs in `BUGS.md`.** Fix those before attempting any of the steps below.

---

## 1. What you need installed

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | **25** | `--enable-preview`, virtual threads |
| Maven | 3.9+ | Build tool |
| Docker Desktop | 24+ with Compose v2 | All infrastructure |

---

## 2. Three things created from scratch

| Item | Status |
|------|--------|
| `Dockerfile` | **NEW** — was completely absent |
| `logback-spring.xml` | **REPLACED** — original was 0 bytes (empty) |
| `.github/workflows/nexus-ledger-service.yml` | **NEW** — no CI existed |

---

## 3. Runtime dependencies

### 3.1 Always required at startup

| Service | Port | Why |
|---------|------|-----|
| **PostgreSQL 16 + pgvector** | 5432 | Ledger entries + postings (SERIALIZABLE) + `financial_literacy_embeddings` for RAG |
| **MongoDB 7** | 27017 | CQRS read model: `account_ledger_summaries`, `posting_documents` |
| **Kafka** | 9092 | `saga.commands` consumer (group: `ledger-service-saga-commands`) + outbox producer |
| **nexus-config-service** | 8888 | `fail-fast: true` — won't start without it |
| **nexus-discovery-service** | 8761 | Service registration |

### 3.2 Optional services

| Service | What breaks without it |
|---------|------------------------|
| **OpenAI API** | Ledger explainer (`LedgerExplainerService`) unavailable; all posting/SAGA operations work fine |
| **nexus-zipkin** | Traces not visible |
| **nexus-loki** | Logs not in Grafana |

---

## 4. Missing dependency — add to pom.xml

```xml
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>1.5.2</version>
</dependency>
```

---

## 5. IMPORTANT: pgvector image required

Like `nexus-account-service`, this service uses Spring AI pgvector for the financial literacy RAG store (`financial_literacy_embeddings`). The `docker-compose.yml` uses `pgvector/pgvector:pg16` — not plain `postgres:16`. The `scripts/postgres-init.sql` installs the extension automatically and sets the default transaction isolation to `SERIALIZABLE` for the entire database.

---

## 6. Kafka topics

| Topic | Direction | Consumer group |
|-------|-----------|---------------|
| `saga.commands` | Consumed | `ledger-service-saga-commands` |
| `ledger.results` | Produced | — (via outbox → Debezium) |

---

## 7. Environment variables

| Variable | Default | Required? |
|----------|---------|-----------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** |
| `DATASOURCE_URL` | `jdbc:postgresql://nexus-postgres:5432/nexus_ledger` | no |
| `POSTGRES_USER` | `nexus` | no |
| `POSTGRES_PASSWORD` | `""` | prod: **YES** |
| `MONGODB_URI` | `mongodb://nexus:nexus_dev@nexus-mongodb:27017/nexus_ledger?authSource=admin` | no |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no |
| `OPENAI_API_KEY` | `""` | no — ledger explainer only |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no |
| `TRACING_SAMPLE_RATE` | `1.0` | no |

---

## 8. How to run (after fixing bugs)

```bash
mvn package -DskipTests
docker compose up -d
docker compose logs -f nexus-ledger-service
curl http://localhost:8088/actuator/health
```

---

## 9. Flyway migrations

| File | Creates |
|------|---------|
| `V1__create_ledger_entries.sql` | `ledger_entries` — immutable append-only event log |
| `V2__create_postings.sql` | `postings` — debit/credit pairs per ledger entry |
| `V3__create_chart_of_accounts.sql` | `chart_of_accounts` — account taxonomy |
| `V4__create_reconciliation_snapshots.sql` | `reconciliation_snapshots` — nightly check results |
| `V5__create_outbox.sql` | `outbox` — Debezium CDC table |

After fixing Bug 1 (directory rename), verify migrations run:
```bash
docker exec nexus-postgres psql -U nexus -d nexus_ledger \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

---

## 10. Reconciliation schedule

`ReconciliationJobService` runs at `0 0 1 * * *` — 1:00 AM daily in Mexico City time (`America/Mexico_City`). This requires `@EnableScheduling` on the main class (fixed as part of Bug 2 resolution).

To verify the scheduler is registered after the fix:
```bash
curl http://localhost:8088/actuator/scheduledtasks
```
