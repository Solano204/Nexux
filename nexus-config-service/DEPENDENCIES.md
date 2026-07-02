# nexus-config-service — Complete Dependency & Run Guide

## What this service is

The **central configuration server** for all 15 Nexus services. Every service loads its configuration from here at startup. If this service is down, **no other service can start** (they all have `fail-fast: true`).

---

## 1. What was created / replaced

| Item | Status |
|------|--------|
| `Dockerfile` | **IMPROVED** — added ZGenerational, MaxGCPauseMillis, MaxMetaspaceSize, security.egd |
| `logback-spring.xml` | **NEW from scratch** — was 0 bytes |
| `.github/workflows/nexus-config-service.yml` | **NEW** — no CI existed |
| `nexus-platform-config/application-prod.yml` | **COMPLETED** — was empty |

---

## 2. No bugs found

This service has a correct `NexusConfigServiceApplication.java` with `@EnableConfigServer` and a proper `main()` method. No source code bugs.

---

## 3. How it works

```
services startup:
  nexus-config-repo-init (alpine/git)
    → creates bare Git repo at /config-repo (Docker volume)
    → copies nexus-platform-config/*.yml into it
    → commits and pushes to bare repo
    → exits (one-shot)

nexus-config-service
  → starts, reads application.yml (in-service config)
  → Config Server clones file:///config-repo
  → Serves config at http://localhost:8888/{service}/{profile}

client services:
  → spring.config.import: "optional:configserver:"
  → GET http://nexus-config-service:8888/nexus-api-gateway/docker
  → receives merged config (application.yml + nexus-api-gateway.yml)
  → if fail-fast: true → service aborts if config unreachable
```

---

## 4. Configuration file hierarchy

When a service like `nexus-api-gateway` starts with `SPRING_PROFILES_ACTIVE=docker`, Config Server merges in order (later overrides earlier):

1. `application.yml` — platform-wide defaults (Kafka, JPA, Eureka, etc.)
2. `application-docker.yml` — docker profile overrides (same as `application-dev.yml` for most)
3. `nexus-api-gateway.yml` — service-specific config (port, routes, etc.)

---

## 5. Config files and what they contain

| File | Purpose |
|------|---------|
| `application.yml` | Platform-wide defaults: Kafka producer/consumer, JPA, Flyway, Eureka, feature flags, all topic names |
| `application-dev.yml` | Dev overrides: DEBUG logging, relaxed rate limits, Swagger enabled |
| `application-prod.yml` | Production overrides: WARN logging, 10% tracing, Swagger disabled |
| `nexus-api-gateway.yml` | Port 8080, all route definitions, CORS, circuit breaker config |
| `nexus-identity-service.yml` | Port 8083, JWT config, password policy, KYC settings |
| `nexus-account-service.yml` | Port 8085, account limits, interest rates, reservation expiry |
| `nexus-transaction-service.yml` | Port 8086, transaction limits, Kafka Streams config |
| `nexus-fraud-service.yml` | Port 8087, fraud thresholds, velocity limits, ReAct agent config |
| `nexus-ledger-service.yml` | Port 8088, reconciliation cron, double-entry config |
| `nexus-notification-service.yml` | Port 8089, channel config, quiet hours, AWS SES |
| `nexus-ai-assistant-service.yml` | Port 8090, AI models, rate limits, safeguard phrases |
| `nexus-ai-kyc-service.yml` | Port 8091, pipeline models, confidence thresholds |
| `nexus-analytics-service.yml` | Port 8092, anomaly detection, Kafka Streams config |
| `nexus-risk-scoring-service.yml` | Port 8094, scoring thresholds |
| `nexus-saga-orchestrator.yml` | Port 8095, saga timeouts |
| `nexus-audit-service.yml` | Port 8096, retention policies |
| `nexus-discovery-service.yml` | Port 8761, Eureka server config |

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

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | — | `docker` or `production` |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | For Cloud Bus |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | Registration |
| `CONFIG_SERVER_USERNAME` | `nexus-config` | Basic auth username |
| `CONFIG_SERVER_PASSWORD` | `nexus-config-password` | Basic auth password — change in prod! |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | Log shipping |

---

## 8. How to run

```bash
mvn package -DskipTests
docker compose up -d

# Verify it's serving config
curl http://nexus-config:nexus-config-password@localhost:8888/nexus-api-gateway/docker
```

---

## 9. Runtime config refresh

To push a config change to all running services without restart:

```bash
# 1. Edit a file in nexus-platform-config/
# 2. Re-run init-config-repo.sh to commit the change
# 3. Trigger refresh on ALL services simultaneously:
curl -X POST http://nexus-config:nexus-config-password@localhost:8888/actuator/busrefresh
```

Services that have `@RefreshScope` beans will reload their config.

---

## 10. Moving to production Git

Replace the file-based Git backend with a real repository:

In `src/main/resources/application.yml`, change:
```yaml
spring.cloud.config.server.git.uri: file:///config-repo
```
to:
```yaml
spring.cloud.config.server.git.uri: https://github.com/your-org/nexus-platform-config
spring.cloud.config.server.git.username: ${GIT_USERNAME}
spring.cloud.config.server.git.password: ${GIT_PASSWORD}
```

The `nexus-platform-config/` directory can then be a separate Git repository.
