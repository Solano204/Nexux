# audit-write-native — Complete Dependency & Run Guide

## ⚠️ READ BUGS.md FIRST

Two critical bugs — most importantly the empty `application.properties`.

---

## 1. This service is DIFFERENT from everything else

| Property | Spring Boot services | This service |
|----------|---------------------|--------------|
| Framework | Spring Boot 3.4 | **Quarkus 3.8.6** |
| Java version | 25 | **21** (GraalVM CE) |
| Startup time | 60-90 seconds | **~90 milliseconds** |
| Idle memory | 256-768 MB | **~28 MB RSS** |
| Build type | JVM JAR | **GraalVM native binary** |
| Config format | `application.yml` | **`application.properties`** |
| Parent POM | `nexus-financial-platform` | **None — standalone module** |
| Spring Cloud Config | Yes (`fail-fast: true`) | **No** (self-contained config) |
| Eureka registration | Yes | **No** (not a Spring service) |
| Main class | `@SpringBootApplication` | **`@QuarkusMain` or none** |

**Build separately — do NOT add to root `<modules>`:**
```bash
cd audit-write-native
mvn package -Pnative -DskipTests   # Native (3-5 min)
# or
mvn package -DskipTests            # JVM (fast iteration)
```

---

## 2. What was created

| Item | Status |
|------|--------|
| `application.properties` | **NEW from scratch** — was 0 bytes; all 15 Kafka channel bindings + ES + MongoDB + Redis |
| `.github/workflows/audit-write-native.yml` | **NEW** — native build CI with container build |
| `scripts/setup-elasticsearch.sh` | **NEW** — creates audit index template |
| `scripts/mongo-init.js` | **NEW** — compliance_alerts collection (7-year TTL) |

---

## 3. Runtime dependencies

| Service | Port | Why |
|---------|------|-----|
| **Elasticsearch 8.13** | 9200 | Audit event writes (op_type=create, idempotent) |
| **MongoDB 7** | 27017 | Compliance alert writes |
| **Redis 7** | 6379 | Velocity window counters (sliding windows) |
| **Kafka** | 9092 | 15-topic fan-in consumer |

No Spring Cloud Config, no Eureka — this service configures itself via `application.properties`.

---

## 4. Kafka channels (15 topics consumed)

| Channel | Kafka Topic |
|---------|-------------|
| `transactions-completed` | `transactions.completed` |
| `transactions-failed` | `transactions.failed` |
| `transactions-initiated` | `transactions.initiated` |
| `ledger-posted` | `ledger.posted` |
| `ledger-reversed` | `ledger.reversed` |
| `fraud-result` | `fraud.result` |
| `fraud-flagged` | `fraud.flagged` |
| `account-frozen` | `account.frozen` |
| `accounts-created` | `accounts.created` |
| `users-registered` | `users.registered` |
| `identity-verified` | `identity.verified` |
| `identity-rejected` | `identity.rejected` |
| `saga-completed` | `saga.completed` |
| `ai-query-logged` | `ai.query.logged` |
| `analytics-anomalies` | `analytics.anomalies.detected` |

---

## 5. Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | Kafka brokers |
| `ELASTICSEARCH_HOSTS` | `http://nexus-elasticsearch:9200` | ES endpoint |
| `ELASTICSEARCH_PASSWORD` | — | prod: **YES** |
| `MONGODB_URI` | `mongodb://nexus:nexus_dev@nexus-mongodb:27017/...` | MongoDB URI |
| `REDIS_URL` | `redis://nexus-redis:6379` | Redis URL |
| `REDIS_PASSWORD` | `""` | prod: **YES** |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | Tracing |
| `TRACING_SAMPLE_RATE` | `1.0` | `0.1` for production |

---

## 6. How to run

### JVM mode (fast iteration — no GraalVM needed):
```bash
# Fix application.properties (see BUGS.md) then:
mvn package -DskipTests
# Add Dockerfile.jvm if you want JVM container,
# or run directly:
java -jar target/quarkus-app/quarkus-run.jar
```

### Native mode (production — 3-5 min build):
```bash
# Requires Docker (uses GraalVM container for compilation)
mvn package -Pnative -DskipTests \
  -Dquarkus.native.container-build=true

docker compose up -d
```

### Verify:
```bash
curl http://localhost:8096/q/health
curl http://localhost:8096/q/metrics | grep audit
```

---

## 7. Compliance rules evaluated on write path

Every inbound event is checked against:
1. **Velocity spike** — 5+ transactions in 5 minutes (Redis sliding window)
2. **Large transaction** — amount > MXN 10,000
3. **High fraud score** — fraud score > 70
4. **CRITICAL severity** — account freezes, document fraud
5. **Structuring** — multiple transactions between MXN 7,000-10,000

Alerts written to MongoDB `compliance_alerts` collection (7-year retention).

---

## 8. Elasticsearch indexing

- Index pattern: `nexus-audit-{YYYY.MM}` (monthly rotation)
- Document ID = `eventId` (UUID from producer)
- `op_type=create` → HTTP 409 on duplicate → silently ignored
- Same event delivered twice = same UUID = idempotent write

---

## 9. Common problems

### All 15 Kafka consumers fail to start
`application.properties` is missing the channel bindings. Use the version from this zip.

### Native build fails with "Image generation failed"
Docker must be running (native build uses a GraalVM container). Check: `docker ps`.

### `vm.max_map_count` error in Elasticsearch (Linux)
```bash
sudo sysctl -w vm.max_map_count=262144
```

### `curl` not found in native container
The `Dockerfile.native` runtime uses `debian:bookworm-slim`. If `curl` is not available, install it in the runtime stage or use `wget` for the healthcheck.
