# nexus-transaction-service — Complete Dependency & Run Guide

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
| **PostgreSQL 16** | 5432 | Transaction records + outbox (V1, V2 Flyway migrations) |
| **Elasticsearch 8.13** | 9200 | Transaction full-text search index (`TransactionSearchDocument`) |
| **Kafka** | 9092 | Streams topologies + 4 consumers + outbox producer |
| **nexus-config-service** | 8888 | `fail-fast: true` — won't start without it |
| **nexus-discovery-service** | 8761 | Registers for API Gateway routing |

### 2.2 Required for full functionality

| Service | Port | What breaks without it |
|---------|------|------------------------|
| **nexus-account-service** | 8085 | SAGA: balance reservation/release replies won't arrive |
| **nexus-fraud-service** | 8087 | Fraud check results never arrive → transactions stuck in `FRAUD_CHECKING` state |
| **nexus-ledger-service** | 8088 | Ledger booking results never arrive → transactions stuck in `LEDGER_PENDING` state |
| **nexus-zipkin** | 9411 | Traces not visible |
| **nexus-loki** | 3100 | Logs not in Grafana |

---

## 3. Kafka topics used

| Topic | Direction | Topology / Consumer |
|-------|-----------|-------------------|
| `transactions.initiated` | Consumed by Streams | `TransactionVelocityTopology` — 5-min velocity windows |
| `transactions.velocity` | Produced by Streams | Output of velocity aggregation |
| `transactions.merchant` | Produced by Streams | Output of `MerchantAggregationTopology` (1-hr windows) |
| `saga.commands` | Produced | SAGA command messages to account/fraud/ledger |
| `saga.replies` | Consumed | `SagaReplyConsumer` |
| `fraud.results` | Consumed | `FraudResultConsumer` |
| `ledger.results` | Consumed | `LedgerResultConsumer` |

All topics are auto-created with `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"`.

---

## 4. ⚠️ CI WORKFLOW WAS DOUBLE-BROKEN — fixed

Your CI file had **two separate issues**:

1. **Wrong directory**: `.worlflows/` (typo) instead of `.github/workflows/`
2. **No `.yml` extension**: file was named `yaml` — GitHub Actions only reads `.yml` files

The fixed file is at: `.github/workflows/nexus-transaction-service.yml`

Delete the broken `.worlflows/` directory from your repo:
```bash
git rm -r .worlflows/
git commit -m "fix: move CI workflow to correct .github/workflows/ location"
```

---

## 5. Missing dependency — add to pom.xml

```xml
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>1.5.2</version>
</dependency>
```

Everything else in `pom.xml` is already correct, including:
- `logstash-logback-encoder:7.4` — already present
- `kafka-streams` / `kafka-streams-test-utils` — aligned at `3.7.0` via `dependencyManagement`
- `spring-boot-starter-data-elasticsearch` — uses ES 8.13.0 via `elasticsearch.version` property

---

## 6. Environment variables reference

| Variable | Default | Required? | Description |
|----------|---------|-----------|-------------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** | `docker`, `dev`, or `production` |
| `DATASOURCE_URL` | `jdbc:postgresql://nexus-postgres:5432/nexus_transaction` | no | PostgreSQL JDBC URL |
| `POSTGRES_USER` | `nexus` | no | DB user |
| `POSTGRES_PASSWORD` | `""` | prod: **YES** | DB password |
| `ELASTICSEARCH_URI` | `http://nexus-elasticsearch:9200` | no | ES endpoint |
| `ELASTICSEARCH_USERNAME` | `elastic` | prod: **YES** | ES user (when security enabled) |
| `ELASTICSEARCH_PASSWORD` | — | prod: **YES** | ES password |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no | Kafka brokers |
| `KAFKA_STREAMS_STATE_DIR` | `/tmp/kafka-streams-state` | prod: **YES** | RocksDB state directory — use a persistent volume in production |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no | Eureka URL |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no | Zipkin URL |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no | Loki URL |
| `TRACING_SAMPLE_RATE` | `1.0` | no | `0.1` for production |
| `ENVIRONMENT` | `local` | no | Metric tag |

---

## 7. Kafka Streams — important notes

### State directory
The velocity and merchant topologies use **RocksDB-backed state stores**. In development these live at `/tmp/kafka-streams-state` (ephemeral — reset every restart, which is fine).

**In production**, mount a persistent volume and set `KAFKA_STREAMS_STATE_DIR=/var/kafka-streams-state`. The `docker-compose.prod.yml` already does this via the `kafka-streams-state-prod` named volume.

If you lose the state directory in production, Kafka Streams rebuilds it from the changelog topics (takes a few minutes, depending on retention).

### Exactly-once semantics
Production uses `processing.guarantee: exactly_once_v2`. This requires:
- Kafka broker with `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1` (set in compose)
- At least one broker (single-broker is fine for dev)
- `isolation.level: read_committed` on consumers (already set)

Dev uses `at_least_once` to make state resets easier.

### Resetting Kafka Streams state (dev)
```bash
# Stop the service, then:
rm -rf /tmp/kafka-streams-state-dev
# Restart service — it rebuilds from Kafka topics
```

---

## 8. How to run

### 8a. Full Docker Compose

```bash
mvn package -DskipTests
docker compose up -d
docker compose logs -f nexus-transaction-service
curl http://localhost:8086/actuator/health
```

### 8b. IDE / local Maven (dev profile)

```bash
docker compose up -d nexus-postgres nexus-elasticsearch \
    nexus-kafka nexus-config-service nexus-discovery-service

mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="--enable-preview" \
  -Dspring-boot.run.profiles=dev
```

### 8c. Tests

```bash
# Unit tests (no Docker — uses kafka-streams-test-utils)
mvn test -Dgroups="unit" --no-transfer-progress

# Integration tests (Docker required — Testcontainers starts PG + Kafka + ES)
mvn test -Dgroups="integration" \
  -DKAFKA_STREAMS_STATE_DIR=/tmp/kafka-streams-test \
  --no-transfer-progress
```

---

## 9. Database migrations

| File | Creates |
|------|---------|
| `V1__create_transactions.sql` | `transactions` table — 16-state lifecycle |
| `V2__create_outbox.sql` | `outbox` — Debezium CDC for event publishing |

---

## 10. Observability

| URL | Description |
|-----|-------------|
| http://localhost:8086/actuator/health | Service health |
| http://localhost:8086/actuator/prometheus | Metrics |
| http://localhost:9200/_cluster/health | Elasticsearch health |
| http://localhost:9200/transactions | Transaction index info |
| http://localhost:3000 | Grafana (admin/admin) |
| http://localhost:9411 | Zipkin |

Key custom metrics:
- `transaction.processing.time` — P95 end-to-end SAGA completion time
- `transaction.state.transitions` — count by state transition
- Kafka Streams built-in metrics on `/actuator/metrics` (prefixed `kafka.streams.*`)

---

## 11. Common problems

### Elasticsearch fails to start with "max virtual memory areas vm.max_map_count [65530] is too low"

```bash
# On Linux host:
sudo sysctl -w vm.max_map_count=262144
# Or permanently:
echo "vm.max_map_count=262144" | sudo tee -a /etc/sysctl.conf
```

On Docker Desktop (Mac/Windows) this is handled automatically.

### Kafka Streams throws "EOS is not supported"
Exactly-once semantics require the Kafka broker to have `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1` set. Already in `docker-compose.yml`. If running against an external broker, ensure `transaction.state.log.replication.factor=1`.

### Transactions stuck in `FRAUD_CHECKING` state
`nexus-fraud-service` is not running or its `fraud.results` consumer is not publishing replies. Start fraud service or use the internal endpoint to manually advance state during testing.

### Elasticsearch index missing
Spring Data Elasticsearch creates the index from `@Document` annotation on `TransactionSearchDocument` on startup. If it fails, run manually:
```bash
./scripts/setup-elasticsearch.sh
```
