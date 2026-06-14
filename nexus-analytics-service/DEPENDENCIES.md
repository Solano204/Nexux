# nexus-analytics-service — Complete Dependency & Run Guide

## ⚠️ READ BUGS.md FIRST

Two bugs must be fixed before the service runs.

---

## 1. What was created / replaced

| Item | Status |
|------|--------|
| `Dockerfile` | **REPLACED** — original had a mkdir/permissions bug (see BUGS.md BUG 2) |
| `logback-spring.xml` | **IMPROVED** — added Loki + ASYNC_LOKI (neverBlock), analytics MDC fields, production profile, explicit root logger |
| `.github/workflows/nexus-analytics-service.yml` | **NEW** — no CI existed |
| `application-dev.yml` | **NEW** — was missing |
| `application-production.yml` | **NEW** — was missing |

---

## 2. What you need installed

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | **25** | `--enable-preview`, virtual threads |
| Maven | 3.9+ | Build tool |
| Docker Desktop | 24+ with Compose v2 | All infrastructure |

---

## 3. Runtime dependencies

### 3.1 Always required

| Service | Port | Why |
|---------|------|-----|
| **Elasticsearch 8.13** | 9200 | `analytics-*` document indices + insights index |
| **Redis 7** | 6379 | Real-time counters, merchant leaderboards (sorted sets), insights cache |
| **Kafka** | 9092 | `transactions.completed` consumer (6 Streams topologies) + `analytics.anomalies` producer |
| **nexus-config-service** | 8888 | `fail-fast: true` |
| **nexus-discovery-service** | 8761 | Service registration |

### 3.2 Optional

| Service | What breaks without it |
|---------|------------------------|
| **OpenAI API** | `InsightGenerationService` degrades to `StatisticalInsightGenerator` (rule-based, no AI text). Service still works, insights are less personalized. |
| **nexus-zipkin** | Traces not visible |
| **nexus-loki** | Logs not in Grafana |

### 3.3 Upstream data sources

This is a **pure CQRS read side** — it never writes financial state. It only processes:

| Producer | Topic consumed |
|---------|---------------|
| nexus-transaction-service | `transactions.completed` |

Without transactions flowing in, the 6 Streams topologies run but produce no output. Start `nexus-transaction-service` and initiate some transactions to see analytics populate.

---

## 4. Six Kafka Streams topologies

| Topology | Window | Purpose |
|----------|--------|---------|
| A: Category spending | Daily tumbling | Per-user spending by category |
| B: Transaction volume | Hourly tumbling | Platform-wide volume metrics |
| C: Merchant frequency | 30-day sliding | User-level merchant visit frequency |
| D: Weekly spending | Weekly tumbling | Baseline for anomaly detection |
| E: Income detection | Monthly | Identifies incoming transfers (income) |
| F: Daily heatmap | Daily tumbling | Data for calendar spending visualization |

All topologies fan out from the same `transactions.completed` stream. State is stored in RocksDB under `/var/kafka-streams` (named Docker volume in compose — persists across container restarts).

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

## 6. Environment variables

| Variable | Default | Required? |
|----------|---------|-----------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** |
| `ELASTICSEARCH_URIS` | `http://nexus-elasticsearch:9200` | no |
| `ELASTICSEARCH_PASSWORD` | — | prod: **YES** |
| `REDIS_HOST` | `nexus-redis` | no |
| `REDIS_PASSWORD` | `""` | prod: **YES** |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no |
| `OPENAI_API_KEY` | `""` | no — statistical fallback if empty |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no |
| `TRACING_SAMPLE_RATE` | `0.1` | no |

---

## 7. How to run

```bash
# 1. Fix Main.java and Dockerfile (see BUGS.md)
# 2. Build
mvn package -DskipTests
# 3. Start
docker compose up -d
docker compose logs -f nexus-analytics-service
curl http://localhost:8092/actuator/health
```

---

## 8. Kafka Streams state directory

The `/var/kafka-streams` directory holds RocksDB state stores for all 6 topologies. In Docker Compose this is a named volume (`kafka-streams-state`) that persists across container restarts.

To reset all Streams state (e.g., to replay from beginning):
```bash
docker compose down
docker volume rm nexus-analytics-service_kafka-streams-state
docker compose up -d
```

After resetting, Streams rebuilds state from the Kafka changelog topics (retention: 7 days in `docker-compose.yml`).

---

## 9. Common problems

### Elasticsearch `vm.max_map_count` too low (Linux only)
```bash
sudo sysctl -w vm.max_map_count=262144
```

### Streams topology fails with "Permission denied: /var/kafka-streams"
The old Dockerfile had this bug. Use the fixed `Dockerfile` from this zip.

### No analytics data appearing
`transactions.completed` topic is empty — `nexus-transaction-service` is not running or no transactions have been initiated. The analytics service processes events reactively; there's no backfill.

### Insights always use statistical fallback
`OPENAI_API_KEY` is empty or invalid. The `StatisticalInsightGenerator` is the fallback — it works correctly but produces templated rather than personalized insights.
