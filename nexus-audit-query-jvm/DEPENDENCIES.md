# nexus-audit-query-jvm — Complete Dependency & Run Guide

## ⚠️ READ BUGS.md FIRST

One bug that prevents startup. Fix it before anything else.

---

## 1. What was created

| Item | Status |
|------|--------|
| `Dockerfile` | **NEW** — was completely absent |
| `logback-spring.xml` | **IMPROVED** — added Loki + ASYNC_LOKI (neverBlock), compliance MDC fields, production profile |
| `.github/workflows/nexus-audit-query-jvm.yml` | **NEW** — no CI existed |
| `application-dev.yml` | **NEW** — was missing |
| `application-production.yml` | **NEW** — was missing |
| `scripts/postgres-init.sql` | **NEW** — creates nexus_audit DB + pgvector extension |
| `scripts/mongo-init.js` | **NEW** — creates compliance_reports collection (7-year TTL) |

---

## 2. What this service does

**Pure CQRS read/query side** — never writes audit events. Compliance officers submit natural-language queries and receive structured `ComplianceQueryResult` with citations.

**Query pipeline (7 steps):**
1. Parse query context (target user, date range, query type)
2. Elasticsearch pre-filter on hard facts (date, userId, event type)
3. Embed filtered events into session-scoped pgvector store
4. Build `RetrievalAugmentationAdvisor` with `MultiQueryExpander`
5. Execute `gpt-4o-mini` with top-K semantic retrieval (temperature 0.1)
6. Enrich result with Elasticsearch event citations
7. Save `ComplianceReport` to MongoDB (7-year CNBV retention)

Concurrent ES + pgvector lookups use **Java 25 JEP 505 `StructuredTaskScope`** — this is why `--enable-preview` is required.

---

## 3. Runtime dependencies

### 3.1 Always required

| Service | Port | Why |
|---------|------|-----|
| **PostgreSQL 16 + pgvector** | 5432 | Audit event embeddings (session-scoped, created per query) |
| **Elasticsearch 8.13** | 9200 | Pre-filter audit event search; provides hard-fact context for RAG |
| **MongoDB 7** | 27017 | `compliance_reports` collection — persists every query result |
| **nexus-config-service** | 8888 | `fail-fast: true` |
| **nexus-discovery-service** | 8761 | Service registration |

### 3.2 Optional

| Service | What breaks |
|---------|------------|
| **OpenAI API** | All compliance queries fail — no fallback. Set `OPENAI_API_KEY`. |
| **nexus-zipkin** | Traces not visible |
| **nexus-loki** | Logs not in Grafana |

### 3.3 Upstream data provider

The audit event data in Elasticsearch is written by:
- **nexus-audit-write** (or audit Lambda functions) — not part of this service

Without audit events in Elasticsearch, queries return empty results (no error — just no matching events to analyse).

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

## 5. Environment variables

| Variable | Default | Required? |
|----------|---------|-----------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** |
| `DATASOURCE_URL` | `jdbc:postgresql://nexus-postgres:5432/nexus_audit` | no |
| `POSTGRES_USER` | `nexus` | no |
| `POSTGRES_PASSWORD` | `""` | prod: **YES** |
| `ELASTICSEARCH_URIS` | `http://nexus-elasticsearch:9200` | no |
| `ELASTICSEARCH_PASSWORD` | — | prod: **YES** |
| `MONGODB_URI` | `mongodb://nexus:nexus_dev@nexus-mongodb:27017/nexus_audit?authSource=admin` | no |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no |
| `OPENAI_API_KEY` | `""` | **YES** — no fallback; all queries fail without it |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no |
| `TRACING_SAMPLE_RATE` | `1.0` | no — use `0.1` for prod |

---

## 6. How to run

```bash
# 1. Fix Main.java (see BUGS.md)
# 2. Build
mvn package -DskipTests
# 3. Start
docker compose up -d
curl http://localhost:8097/actuator/health
```

---

## 7. Compliance report retention

The `scripts/mongo-init.js` sets a 7-year TTL on the `compliance_reports` collection. This aligns with CNBV (Mexican banking regulator) record retention requirements. Do not reduce this TTL.

---

## 8. Common problems

### pgvector extension missing
```bash
docker exec nexus-postgres psql -U nexus -d nexus_audit \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
docker compose restart nexus-audit-query-jvm
```

### No audit events returned
Elasticsearch `audit-events-*` index is empty — `nexus-audit-write` or Lambda functions have not yet populated it. This is not an error — the service returns an empty result set.

### StructuredTaskScope / JEP 505 compilation error
Ensure `--enable-preview` is set. The `pom.xml` already configures this via root `pluginManagement`. If building outside the monorepo, add to `maven-compiler-plugin`:
```xml
<enablePreview>true</enablePreview>
<release>25</release>
```
