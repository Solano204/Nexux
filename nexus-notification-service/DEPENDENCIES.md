# nexus-notification-service — Complete Dependency & Run Guide

## 1. What you need installed

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | **25** | `--enable-preview`, virtual threads |
| Maven | 3.9+ | Build tool |
| Docker Desktop | 24+ with Compose v2 | All infrastructure |

---

## 2. ⚠️ DOCKERFILE WAS MISSING — created

The service had **no Dockerfile**. A `Dockerfile` has been created and is included in this zip. Place it at the root of `nexus-notification-service/` alongside `pom.xml`.

---

## 3. ⚠️ NO CI WORKFLOW — created

The service had **no `.github/workflows/` file**. A complete CI pipeline has been created at `.github/workflows/nexus-notification-service.yml`.

---

## 4. Runtime dependencies

### 4.1 Always required at startup

| Service | Port | Why |
|---------|------|-----|
| **MongoDB 7** | 27017 | `notifications` + `user_notification_preferences` + `notification_templates` |
| **Redis 7** | 6379 | Notification dedup cache — prevents duplicate sends |
| **Kafka** | 9092 | 4 consumer groups across 8 topics (see section 5) |
| **nexus-config-service** | 8888 | `fail-fast: true` — won't start without it |
| **nexus-discovery-service** | 8761 | Service registration |

### 4.2 Required for full functionality

| Service / External | What breaks without it |
|-------------------|------------------------|
| **OpenAI API** | `NotificationContentGenerator` fails; `FallbackContentGenerator` kicks in automatically with template-based content. All channels still deliver, just without personalized AI copy. |
| **nexus-zipkin** | Traces not visible |
| **nexus-loki** | Logs not in Grafana |

### 4.3 Event producers this service depends on

This service is a **pure consumer** — it never produces events. It reacts to events produced by:

| Producer | Topics consumed |
|---------|----------------|
| nexus-identity-service | `users.registered`, `identity.verified`, `identity.rejected` |
| nexus-account-service | `accounts.created` |
| nexus-fraud-service | `fraud.flagged` |
| nexus-transaction-service | `transactions.completed`, `transactions.failed` |
| nexus-saga-orchestrator | `saga.commands` (NOTIFY_* command types) |

---

## 5. Kafka consumer groups and topics

| Consumer class | Group ID | Topics |
|---------------|----------|--------|
| `IdentityEventConsumer` | `notification-service-identity` | `users.registered`, `identity.verified`, `identity.rejected`, `accounts.created` |
| `FraudEventConsumer` | `notification-service-fraud` | `fraud.flagged` |
| `TransactionEventConsumer` | `notification-service-transactions` | `transactions.completed`, `transactions.failed` |
| `SagaCommandConsumer` | `notification-service-saga` | `saga.commands` |

All topics are auto-created by Kafka. `ack-mode: manual` ensures no message is acknowledged until successfully processed and stored in MongoDB.

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

## 7. Environment variables reference

| Variable | Default | Required? | Description |
|----------|---------|-----------|-------------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** | `docker`, `dev`, or `production` |
| `MONGODB_URI` | `mongodb://nexus:nexus_dev@nexus-mongodb:27017/nexus_notification?authSource=admin` | no | MongoDB connection string |
| `REDIS_HOST` | `nexus-redis` | no | Redis host |
| `REDIS_PORT` | `6379` | no | Redis port |
| `REDIS_PASSWORD` | `""` | prod: **YES** | Redis password |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no | Kafka brokers |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no | Eureka URL |
| `OPENAI_API_KEY` | `""` | no | Empty = `FallbackContentGenerator` used |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no | Zipkin URL |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no | Loki push URL |
| `TRACING_SAMPLE_RATE` | `1.0` | no | `0.1` for production |
| `ENVIRONMENT` | `local` | no | Metric tag |

---

## 8. How to run

### 8a. Full Docker Compose

```bash
# Build JAR first (Dockerfile references the built jar)
mvn package -DskipTests
docker compose up -d
docker compose logs -f nexus-notification-service
curl http://localhost:8089/actuator/health
```

### 8b. IDE / local Maven (dev profile)

```bash
docker compose up -d nexus-mongodb nexus-redis nexus-kafka \
    nexus-config-service nexus-discovery-service

mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="--enable-preview" \
  -Dspring-boot.run.profiles=dev
```

### 8c. Tests

```bash
# Unit tests (no Docker)
mvn test -Dgroups="unit" --no-transfer-progress

# Integration tests (Docker required — Testcontainers)
mvn test -Dgroups="integration" \
  -DOPENAI_API_KEY=test-mock \
  --no-transfer-progress
```

---

## 9. AI content generation — how it works

1. A Kafka event arrives (e.g., `transactions.completed`)
2. `NotificationProcessingService` calls `NotificationContentGenerator`
3. `NotificationContentGenerator` calls OpenAI GPT-4o-mini with a structured output prompt
4. On success: personalized JSON content used for all channels
5. On failure (API error, timeout, or empty key): `FallbackContentGenerator` uses MongoDB templates from the `notification_templates` collection
6. The Resilience4j retry (`openai-retry`: 3 attempts, exponential backoff) wraps the OpenAI call

The `scripts/mongo-init.js` pre-loads fallback templates for common event types in both Spanish and English.

---

## 10. MongoDB collections

| Collection | Purpose |
|-----------|---------|
| `notifications` | All sent notifications (90-day TTL index) |
| `user_notification_preferences` | Per-user channel preferences and language settings |
| `notification_templates` | Fallback templates when OpenAI unavailable |

---

## 11. Observability

| URL | Description |
|-----|-------------|
| http://localhost:8089/actuator/health | Service health |
| http://localhost:8089/actuator/prometheus | Metrics |
| http://localhost:3000 | Grafana (admin/admin) |
| http://localhost:9411 | Zipkin |

Key custom metric: `notification.ai.generation.duration` — P95 latency of OpenAI content generation calls (P99 helps detect model slowdowns before they affect SLA).

---

## 12. Common problems

### Service starts but no notifications are delivered
Check that the upstream services are publishing to the correct topics:
```bash
# List consumer group lag
docker exec nexus-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group notification-service-transactions \
  --describe
```

### "MongoTimeoutException" on startup
MongoDB container not healthy yet.
```bash
docker compose ps nexus-mongodb
# Wait for "(healthy)", then:
docker compose restart nexus-notification-service
```

### All notifications using fallback templates instead of AI
`OPENAI_API_KEY` is empty or invalid. This is intentional by design — the fallback ensures delivery without AI. Set a valid key to re-enable personalized content.

### Spring AI milestone dependency not found
Add the Spring Milestones repo (already in your `pom.xml` `<repositories>` block). If building in a restricted environment, mirror `https://repo.spring.io/milestone`.
