# nexus-saga-orchestrator — Complete Dependency & Run Guide

## ⚠️ READ BUGS.md FIRST

Two bugs — fix them before running.

---

## 1. What was created / replaced

| Item | Status |
|------|--------|
| `Dockerfile` | **REPLACED** — added ZGenerational, increased heap 512m→768m, MaxMetaspaceSize |
| `logback-spring.xml` | **NEW** — the service had no logback config at all |
| `.github/workflows/nexus-saga-orchestrator.yml` | **NEW** — no CI existed |
| `application-dev.yml` | **NEW** — was missing |
| `application-production.yml` | **NEW** — was missing |
| `scripts/postgres-init.sql` | **NEW** |

---

## 2. What this service does

The SAGA orchestrator is the **conductor of every distributed transaction** on the platform. Without it, no onboarding completes and no transfer goes through.

### Two sagas orchestrated

**OnboardingFlowSaga** — triggered by `users.registered`:
```
STARTED → KYC_PENDING → KYC_VERIFIED → ACCOUNT_CREATION_PENDING
       → ACCOUNT_CREATED → COMPLETED
```
Any step failure triggers compensation with user-facing explanation via Spring AI.

**TransferSaga** — triggered by `transactions.initiated`:
```
STARTED → BALANCE_RESERVATION_PENDING → FRAUD_CHECK_PENDING
       → LEDGER_PENDING → NOTIFICATION_PENDING → COMPLETED
```
Fraud rejection or ledger failure triggers full compensation (balance released).

### SagaTimeoutMonitor
Polls every 5 seconds for saga steps that have exceeded their timeout. Triggers compensation automatically. Requires `@EnableScheduling` on Main.java (see BUG 1).

---

## 3. Runtime dependencies

### 3.1 Always required at startup

| Service | Port | Why |
|---------|------|-----|
| **PostgreSQL 16** | 5432 | Saga state (onboarding_sagas, transfer_sagas), step history, timeouts, outbox |
| **Kafka** | 9092 | 4 consumers + 1 producer — the entire saga communication bus |
| **nexus-config-service** | 8888 | `fail-fast: true` |
| **nexus-discovery-service** | 8761 | Service registration |

### 3.2 Saga participants (must be running for sagas to complete)

| Service | Port | Commands it receives |
|---------|------|---------------------|
| nexus-identity-service | 8083 | `VERIFY_KYC`, `CREATE_ACCOUNT_FOR_USER` |
| nexus-account-service | 8085 | `RESERVE_BALANCE`, `RELEASE_BALANCE`, `FINALIZE_BALANCE` |
| nexus-fraud-service | 8087 | `CHECK_FRAUD` |
| nexus-ledger-service | 8088 | `BOOK_LEDGER_ENTRY` |
| nexus-notification-service | 8089 | `NOTIFY_TRANSFER_COMPLETED`, `NOTIFY_TRANSFER_FAILED` |
| nexus-ai-kyc-service | 8091 | `KYC_VERIFY` |

Without these services, sagas start but time out and compensate (no permanent harm).

### 3.3 Optional

| Service | What breaks |
|---------|------------|
| **OpenAI API** | `SagaFailureExplainerService` falls back to generic template messages |
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

## 5. Kafka topology

| Direction | Topics | Consumer groups |
|-----------|--------|----------------|
| Consumed | `users.registered` | `saga-orchestrator-identity` |
| Consumed | `identity.verified`, `identity.rejected` | `saga-orchestrator-kyc` |
| Consumed | `transactions.initiated` | `saga-orchestrator-transactions` |
| Consumed | `saga.replies` | `saga-orchestrator-replies` |
| Produced | `saga.commands` | — (to all participants) |

---

## 6. Environment variables

| Variable | Default | Required? |
|----------|---------|-----------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** |
| `DATASOURCE_URL` | `jdbc:postgresql://nexus-postgres:5432/nexus_saga` | no |
| `POSTGRES_USER` | `nexus` | no |
| `POSTGRES_PASSWORD` | `""` | prod: **YES** |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no |
| `OPENAI_API_KEY` | `""` | no — fallback template if empty |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no |
| `TRACING_SAMPLE_RATE` | `1.0` | no |

---

## 7. Flyway migrations

All 5 migrations are in `db/migration/` (correct directory — no bug here):

| File | Creates |
|------|---------|
| `V1__create_onboarding_sagas.sql` | `onboarding_sagas` table |
| `V2__create_transfer_sagas.sql` | `transfer_sagas` table |
| `V3__create_saga_step_history.sql` | `saga_step_history` (append-only) |
| `V4__create_saga_timeouts.sql` | `saga_timeouts` (polled by monitor) |
| `V5__create_outbox.sql` | `outbox` (Debezium CDC) |

---

## 8. How to run

```bash
# 1. Fix Main.java (see BUGS.md) — critical
# 2. Build
mvn package -DskipTests
# 3. Start
docker compose up -d
curl http://localhost:8095/actuator/health
# 4. Verify scheduler is active
curl http://localhost:8095/actuator/scheduledtasks
# Should show: fixedDelay=5000 for SagaTimeoutMonitor
```

---

## 9. Common problems

### Sagas never complete (stuck in pending state)
Check participant services are running and consuming `saga.commands`:
```bash
docker exec nexus-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group ledger-service-saga-commands --describe
```

### SagaTimeoutMonitor not running
`@EnableScheduling` missing from Main.java. See BUG 1 in BUGS.md.

### Duplicate saga commands sent
Kafka consumer not acknowledging messages (`ack-mode: manual` requires explicit `Acknowledgment.acknowledge()`). Check `SagaReplyConsumer` — it must call `ack.acknowledge()` after processing.

### Funds stuck as reserved after failed transfer
Compensation ran but `RELEASE_BALANCE` command was not sent or processed. Check:
- `saga_step_history` for the failed saga
- `transfer_sagas` table for saga state
- Kafka `saga.commands` topic for the release command
