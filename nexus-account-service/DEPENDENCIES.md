# nexus-account-service — Complete Dependency & Run Guide

## 1. What you need installed

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | **25** | `--enable-preview`, virtual threads |
| Maven | 3.9+ | Build tool |
| Docker Desktop | 24+ with Compose v2 | All infrastructure |

---

## 2. Runtime dependencies

### 2.1 Always required at startup

| Service | Port | Why |
|---------|------|-----|
| **PostgreSQL 16 + pgvector** | 5432 | Account data (Hikari pool 50) + pgvector table `transaction_embeddings` for AI RAG |
| **MongoDB 7** | 27017 | `account_analytics` collection (CQRS read model) + AI advisor memory |
| **Redis 7** | 6379 | Balance cache (30s TTL) + reservation distributed lock (`account:reservation-lock:{id}`) |
| **nexus-config-service** | 8888 | Config loaded at startup — `fail-fast: true` |
| **nexus-discovery-service** | 8761 | Service registration + route resolution |

### 2.2 Required for full functionality

| Service | Port | What breaks without it |
|---------|------|------------------------|
| **Kafka** | 9092 | SAGA command consumer doesn't start; outbox events can't publish |
| **nexus-zipkin** | 9411 | Traces not visible |
| **nexus-loki** | 3100 | Logs not in Grafana |
| **OpenAI API** | external | `AccountAdvisorService` RAG pipeline fails; `/api/v1/accounts/{id}/advisor/**` returns 503. Other account operations work fine. |

### 2.3 Downstream consumers

| Service | What it consumes |
|---------|-----------------|
| nexus-saga-orchestrator | `accounts.created`, `accounts.balance-reserved`, `accounts.balance-released`, `accounts.balance-finalized` (all via Debezium→Kafka from outbox) |
| nexus-analytics-service | `accounts.created` for user dimension KTable |
| nexus-audit-service | All account events |

---

## 3. ⚠️ CRITICAL: pgvector vs plain PostgreSQL

**You must use `pgvector/pgvector:pg16` — NOT `postgres:16`.**

The account service uses Spring AI's pgvector store for the account advisor RAG pipeline. This requires the `vector` extension in PostgreSQL. The plain `postgres:16` image does NOT include it.

The `docker-compose.yml` already uses the correct image. The `scripts/postgres-init.sql` runs `CREATE EXTENSION IF NOT EXISTS vector;` automatically.

If you're connecting to an existing PostgreSQL instance, manually install pgvector:
```sql
-- Connect as superuser:
CREATE EXTENSION IF NOT EXISTS vector;
```

Spring AI's `initialize-schema: true` creates the `transaction_embeddings` table with a `vector(1536)` column — this requires the extension to exist first.

---

## 4. Missing dependency — add to pom.xml

The `logback-spring.xml` uses the Loki4j appender. Add this to `<dependencies>`:

```xml
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>1.5.2</version>
</dependency>
```

Everything else in `pom.xml` is already present.

### Spring AI milestone repository

Your `pom.xml` declares `spring-ai.version=1.0.0-M6` which is a milestone release and requires the Spring Milestones repository. This is already configured at the bottom of your `pom.xml`. If builds fail to resolve Spring AI artifacts, verify this block is present:

```xml
<repository>
    <id>spring-milestones</id>
    <name>Spring Milestones</name>
    <url>https://repo.spring.io/milestone</url>
    <snapshots><enabled>false</enabled></snapshots>
</repository>
```

---

## 5. Environment variables reference

| Variable | Default | Required? | Description |
|----------|---------|-----------|-------------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** | `docker`, `dev`, or `production` |
| `DATASOURCE_URL` | `jdbc:postgresql://nexus-postgres:5432/nexus_account` | no | PostgreSQL JDBC URL (also used by pgvector store) |
| `POSTGRES_USER` | `nexus` | no | DB user |
| `POSTGRES_PASSWORD` | `""` | prod: **YES** | DB password |
| `MONGODB_URI` | `mongodb://nexus:nexus_dev@nexus-mongodb:27017/nexus_account?authSource=admin` | no | MongoDB URI |
| `REDIS_HOST` | `nexus-redis` | no | Redis host |
| `REDIS_PORT` | `6379` | no | Redis port |
| `REDIS_PASSWORD` | `""` | prod: **YES** | Redis password |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no | Kafka brokers |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no | Eureka URL |
| `OPENAI_API_KEY` | `""` | no | AI advisor. Empty = advisor endpoint returns error, all other endpoints work |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no | Zipkin URL |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no | Loki push URL |
| `TRACING_SAMPLE_RATE` | `1.0` | no | `0.1` for production |
| `ENVIRONMENT` | `local` | no | Metric tag |

---

## 6. How to run

### 6a. Full Docker Compose

```bash
mvn package -DskipTests
docker compose up -d
docker compose logs -f nexus-account-service
curl http://localhost:8085/actuator/health
```

### 6b. IDE / local Maven (dev profile)

```bash
# Start infrastructure only
docker compose up -d nexus-postgres nexus-mongodb nexus-redis \
    nexus-kafka nexus-config-service nexus-discovery-service

# Run service
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="--enable-preview" \
  -Dspring-boot.run.profiles=dev
```

### 6c. Tests

```bash
# Unit tests (no Docker)
mvn test -Dgroups="unit" --no-transfer-progress

# Integration tests (requires Docker — Testcontainers starts pgvector+mongo+kafka)
mvn test -Dgroups="integration" \
  -DOPENAI_API_KEY=test-mock \
  --no-transfer-progress
```

---

## 7. Database setup

### PostgreSQL migrations (Flyway — auto-runs at startup)

| File | Creates |
|------|---------|
| `V1__create_accounts.sql` | `accounts` table with balance constraints |
| `V2__create_balance_reservations.sql` | `balance_reservations` (SELECT FOR UPDATE target) |
| `V3__create_account_events.sql` | `account_events` append-only log |
| `V4__create_outbox.sql` | `outbox` for Debezium CDC |
| `V5__create_daily_limit_usage.sql` | `daily_limit_usage` for rate limiting |

**pgvector table (Spring AI auto-creates):**
```sql
-- Created by Spring AI when initialize-schema: true
CREATE TABLE transaction_embeddings (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(1536)
);
CREATE INDEX ON transaction_embeddings USING hnsw (embedding vector_cosine_ops);
```

### MongoDB collections (auto-created)

| Collection | Purpose |
|-----------|---------|
| `account_analytics` | Pre-aggregated CQRS read model per account |
| `account_advisor_memory` | AI advisor session memory (30-day TTL) |

---

## 8. Observability

| URL | Description |
|-----|-------------|
| http://localhost:8085/actuator/health | Service health |
| http://localhost:8085/actuator/prometheus | Prometheus metrics |
| http://localhost:3000 | Grafana (admin/admin) |
| http://localhost:9090 | Prometheus |
| http://localhost:9411 | Zipkin traces |

Key custom metrics:
- `account.balance.reservation.duration` — P95 time to reserve balance (includes SELECT FOR UPDATE)
- `account.balance.lock.wait.duration` — Redis reservation lock wait time
- `account.saga.commands.processed` — SAGA command throughput

---

## 9. Common problems

### "column embedding of relation transaction_embeddings does not exist"
pgvector extension not installed before Spring AI tried to create the table.
```bash
# Connect to PostgreSQL and install:
docker exec nexus-postgres psql -U nexus -d nexus_account \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
# Then restart the service:
docker compose restart nexus-account-service
```

### "MongoTimeoutException: Timed out after 30000ms"
MongoDB is not up. Check: `docker compose ps nexus-mongodb`

### "HikariPool-1 - Connection timeout after 30002ms"
PostgreSQL pool exhausted. Common cause: SELECT FOR UPDATE holding connections.
In dev, reduce concurrency. In prod, check for stuck transactions:
```sql
SELECT pid, state, wait_event_type, wait_event, query_start, query
FROM pg_stat_activity WHERE state != 'idle' ORDER BY query_start;
```

### Spring AI fails with "Model not found" or 401
Check `OPENAI_API_KEY` is set correctly. The account advisor is the only part that needs it — all balance/SAGA operations work without it.

### "Artifact spring-ai-pgvector-store-spring-boot-starter not found"
The Spring Milestones repository is missing from `pom.xml` `<repositories>`.
Add it (see section 4 above).
