# NEXUS Platform — Full Configuration Audit & Fix Log

**Date:** 2026-06-13  
**Author:** Claude Sonnet 4.6 (claude-sonnet-4-6) via Claude Code  
**Scope:** Configuration files only — no application code was modified  
**Services audited:** 18 folders (15 Spring Boot microservices, 1 Quarkus native service, 1 shared Kafka infra, 1 Lambda collection)

---

## Process Overview

The work was split into 3 phases, each executed as a parallel multi-agent workflow:

| Phase | Workflow | Agents spawned | Purpose |
|---|---|---|---|
| 1 | `nexus-full-config-audit` | 20 agents | Read all configs, produce architecture report |
| 2 | `nexus-config-implementation` | 18 agents | Apply all fixes in parallel |
| 3 | Manual (inline) | — | Summary + remaining items identified |

---

## Phase 1 — Full Architecture Audit

### How it worked

A workflow launched **17 parallel reader agents** (one per service folder) plus dedicated agents for:
- `nexus-config-service` + `nexus-platform-config` (all 31 config repo files)
- `kafka/` infrastructure
- A final **synthesis agent** that received all raw file content and produced a structured report

Each reader agent read these files per service (where present):
```
src/main/resources/application.yml
src/main/resources/application-dev.yml
src/main/resources/application-docker.yml
src/main/resources/application-prod.yml
src/main/resources/logback-spring.xml
Dockerfile
docker-compose.yml
docker-compose.prod.yml
.env
pom.xml (first 80 lines)
.github/workflows/
```

### What the synthesis agent found

The synthesis agent produced a full architecture report covering:

1. **Service inventory** — 24 deployable units total (15 Spring Boot, 1 Quarkus, 8 Lambda functions)
2. **Port map** — all 27 ports assigned across all services + infrastructure
3. **Missing config files** per service
4. **10 categories of inconsistencies** across services
5. **Observability gaps** — Prometheus, Loki, Grafana, Zipkin coverage
6. **nexus-platform-config gaps** — missing or mismatched entries
7. **Recommended standard templates** for docker, prod, logback, Dockerfile

---

## Phase 2 — Implementation (18 parallel agents)

### Fix 1 — Kafka docker-compose port conflict & listener fix

**File:** `kafka/docker-compose.yml`  
**Agent label:** `fix:kafka-compose`

**Problem:** Two conflicts existed:
- Kafka UI was mapped to host port `8090:8080`, which conflicts with `nexus-ai-assistant-service` (also port 8090)
- Kafka internal listener advertised `nexus-kafka:29092` but ALL Spring Boot services reference `nexus-kafka:9092`. Containers inside the Docker network were unable to reach the broker.

**Changes made:**

| Component | Field | Before | After |
|---|---|---|---|
| nexus-kafka | `ports` | `9092:9092` | `19092:19092` |
| nexus-kafka | `KAFKA_LISTENERS` | `PLAINTEXT://nexus-kafka:29092,CONTROLLER://...,PLAINTEXT_HOST://0.0.0.0:9092` | `PLAINTEXT_INTERNAL://0.0.0.0:9092,PLAINTEXT_EXTERNAL://0.0.0.0:19092,CONTROLLER://nexus-kafka:9093` |
| nexus-kafka | `KAFKA_ADVERTISED_LISTENERS` | `PLAINTEXT://nexus-kafka:29092,PLAINTEXT_HOST://localhost:9092` | `PLAINTEXT_INTERNAL://nexus-kafka:9092,PLAINTEXT_EXTERNAL://localhost:19092` |
| nexus-kafka | `KAFKA_LISTENER_SECURITY_PROTOCOL_MAP` | `PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT` | `PLAINTEXT_INTERNAL:PLAINTEXT,PLAINTEXT_EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT` |
| nexus-kafka | `KAFKA_INTER_BROKER_LISTENER_NAME` | `PLAINTEXT` | `PLAINTEXT_INTERNAL` |
| nexus-kafka | healthcheck bootstrap | `localhost:9092` | `localhost:19092` |
| nexus-kafka-ui | `ports` | `8090:8080` | `8190:8080` |
| nexus-kafka-ui | `KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS` | `nexus-kafka:29092` | `nexus-kafka:9092` |
| nexus-kafka-topics | `--bootstrap-server` (×30 topics) | `nexus-kafka:29092` | `nexus-kafka:9092` |

**Net result:** Containers reach broker at `nexus-kafka:9092` (PLAINTEXT_INTERNAL). External/dev tools reach it at `localhost:19092`. Kafka UI no longer conflicts with nexus-ai-assistant-service.

---

### Fix 2 — nexus-discovery-service application-dev.yml (corrupted)

**File:** `nexus-discovery-service/src/main/resources/application-dev.yml`  
**Agent label:** `fix:discovery-dev`

**Problem:** The file contained nexus-fraud-service **production** config (port 8087, Kafka bootstrap, env-var-driven config server, production Eureka client). This meant running discovery-service with `-Dspring.profiles.active=dev` would boot it as fraud-service config, causing port conflicts and misbehavior.

**Action:** File fully replaced with correct discovery-service dev profile:
- Spring Cloud Config + Bus disabled (not needed for self-contained discovery server)
- Port `8761`
- Eureka standalone server mode (`register-with-eureka: false`, `fetch-registry: false`)
- Self-preservation disabled for faster dev feedback
- Tracing disabled (no Zipkin dependency in dev)
- Eureka-specific logging at INFO

---

### Fix 3 — nexus-fraud-service application-docker.yml (incomplete)

**File:** `nexus-fraud-service/src/main/resources/application-docker.yml`  
**Agent label:** `fix:fraud-docker`

**Problem:** The docker profile contained only 4 lines:
```yaml
spring.datasource.url
spring.data.redis.host
spring.kafka.bootstrap-servers
spring.elasticsearch.uris
```
Every other service's docker profile is 60-80 lines with full Config Server credentials, Spring Cloud Bus, Hikari pool tuning, Eureka health check path, and management endpoints. The fraud service also used the wrong key `spring.elasticsearch` (bare) instead of `spring.data.elasticsearch` (where Spring Boot auto-config expects it).

**Action:** Full docker profile written with:
- Config Server credentials block (`username`, `password`, retry policy)
- Spring Cloud Bus enabled
- Hikari pool: max 20, min 5, `connection-timeout: 5000`, pool-name, `application_name`
- JPA: `validate` ddl-auto, `provider_disables_autocommit`, batch settings
- Flyway: `validate-on-migrate: true`
- `spring.data.elasticsearch` (corrected key path)
- pgvector: `initialize-schema: false` (Flyway handles schema in docker)
- Full management block: all endpoints exposed, metrics tags with `environment: docker`, tracing `probability: 1.0`
- Eureka health-check URL path

---

### Fix 4 — nexus-saga-orchestrator (profile files check)

**Agent label:** `fix:saga-profiles`

**Audit finding:** All 3 profile files appeared missing.  
**Actual result:** All 3 files existed with correct content. The audit had read from a stale zip copy. No writes needed.

---

### Fix 5 — Tracing sampling probability (analytics + risk-scoring)

**Files:**  
- `nexus-analytics-service/src/main/resources/application.yml`  
- `nexus-risk-scoring-service/src/main/resources/application.yml`  
**Agent label:** `fix:tracing-sampling`

**Problem:** Both services hardcoded `probability: 0.1` (10%) in their BASE `application.yml`. All other services default to `1.0` in base config and only reduce to `0.1` in the production profile. This meant analytics and risk-scoring traces were sparse during development, making debugging very difficult.

**Changes:**
- `nexus-analytics-service/application.yml` line 91: `0.1` → `1.0`
- `nexus-risk-scoring-service/application.yml` line 109: `0.1` → `1.0` (stale comment removed)

---

### Fix 6 — nexus-platform-config: create nexus-audit-query-jvm entries

**Files created:**  
- `nexus-config-service/nexus-platform-config/nexus-audit-query-jvm.yml`  
- `nexus-config-service/nexus-platform-config/nexus-audit-query-jvm-dev.yml`  
**Agent label:** `fix:audit-query-platform-config`

**Problem:** The `nexus-audit-query-jvm` service registers with `spring.application.name: nexus-audit-query-jvm` and fetches from the Config Server, but no matching file existed in the config repo. The service was silently falling back to `application.yml` global defaults only — missing all service-specific Elasticsearch, MongoDB, pgvector, and Hikari configuration.

**Production file contents:**
- Port 8097, Hikari pool (max 20, min 5), JPA validate mode, Flyway migrations
- Elasticsearch with auth credentials, MongoDB URI, OpenAI (gpt-4o-mini, temp 0.3)
- pgvector with `initialize-schema: false`, table `audit_embeddings`
- Eureka with health-check path, full metrics/tracing (sample rate 0.1 for prod)

**Dev file contents:**
- Localhost overrides for Postgres, Elasticsearch, MongoDB
- `show-sql: true`, `format_sql: true`, trace rate 1.0

---

### Fix 7 — nexus-platform-config: nexus-fraud-service-dev.yml duplicate YAML key

**File:** `nexus-config-service/nexus-platform-config/nexus-fraud-service-dev.yml`  
**Agent label:** `fix:fraud-dev-platform`

**Problem:** The file had two separate `spring.data:` top-level keys. YAML parsers process duplicate keys by using only the last one, silently dropping the first. This meant either MongoDB or Redis config was never loaded for the fraud service in dev.

**Action:** Both `spring.data.mongodb` and `spring.data.redis` blocks merged under a single `spring.data:` parent. Values unchanged — only YAML structure fixed.

---

### Fix 8 — nexus-platform-config: nexus-notification-service.yml missing Redis

**File:** `nexus-config-service/nexus-platform-config/nexus-notification-service.yml`  
**Agent label:** `fix:notification-platform`

**Problem:** The notification service uses Redis for rate-limiting and message deduplication, but the production platform config file had no `spring.data.redis` block at all. In Docker/production, the service would fail to connect to Redis or use zero configuration defaults.

**Action:** Added Redis block under `spring.data` (sibling of existing `mongodb`):
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:nexus-redis}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 100ms
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5
```

---

### Fix 9 — Database name normalization (account + transaction)

**Files modified:**  
- `nexus-account-service/src/main/resources/application.yml`  
- `nexus-account-service/src/main/resources/application-dev.yml`  
- `nexus-transaction-service/src/main/resources/application.yml`  
- `nexus-transaction-service/src/main/resources/application-dev.yml`  
**Agent label:** `fix:db-names`

**Problem:** Production configs in `nexus-platform-config` used plural DB names (`nexus_accounts`, `nexus_transactions`) but local service configs used singular (`nexus_account`, `nexus_transaction`). This meant Flyway migrations would target different schemas depending on the active profile, potentially running migrations twice or on the wrong database.

**Decision:** Plural form adopted as the standard (already used in production platform-config).

**Changes:**
- `nexus-account-service/application.yml` line 29: `nexus_account` → `nexus_accounts`
- `nexus-account-service/application-dev.yml` line 24: `nexus_account` → `nexus_accounts`
- `nexus-transaction-service/application.yml`: `nexus_transaction` → `nexus_transactions`
- `nexus-transaction-service/application-dev.yml`: `nexus_transaction` → `nexus_transactions`

---

### Fix 10 — nexus-platform-config: nexus-ai-assistant-service-dev.yml missing Redis + pgvector

**File:** `nexus-config-service/nexus-platform-config/nexus-ai-assistant-service-dev.yml`  
**Agent label:** `fix:ai-assistant-platform`

**Problem:** Dev platform config was missing Redis and pgvector overrides. The ai-assistant service uses Redis for session caching and pgvector for embedding storage. Without dev overrides, it would attempt to connect to the Docker hostnames (`nexus-redis`, etc.) when running locally.

**Action:** Added under `spring`:
- `spring.data.redis`: `localhost:6379`, no password, `500ms` timeout
- `spring.ai.vectorstore.pgvector`: `initialize-schema: true`, HNSW index, COSINE_DISTANCE, table `ai_assistant_embeddings`

---

### Fix 11 — nexus-config-service GIT_TOKEN insecure default

**File:** `nexus-config-service/src/main/resources/application.yml`  
**Agent label:** `fix:config-service-token`

**Problem:** The config server's Git authentication had:
```yaml
password: ${GIT_TOKEN:your-token-here}
```
This placeholder default would allow the service to start without the env var set, silently authenticating to GitHub with the literal string `your-token-here`. GitHub would reject it, but the service would not fail fast — it would start up and only fail when a client tried to fetch config.

**Change:** Line 20 changed to `password: ${GIT_TOKEN}` (no default). Service now fails fast at startup if `GIT_TOKEN` is not injected.

---

### Fix 12 — nexus-fraud-service logback-spring.xml (no Loki appender)

**File:** `nexus-fraud-service/src/main/resources/logback-spring.xml`  
**Agent label:** `fix:fraud-logback`

**Problem:** The fraud service had a logback config with JSON console output only. No Loki4j appender — logs never reached Loki/Grafana. Also the profile guard used `!docker` (misses `production` profile).

**Action:** Full rewrite with:
- `springProperty` bindings for `appName`, `appVersion`, `environment`, `lokiUrl`
- `!docker,!production` profile: plain pattern encoder for human-readable dev output
- `docker,production` profile: LogstashEncoder + Loki4jAppender (async, queueSize 4096, neverBlock)

---

### Fix 13 — nexus-ai-assistant-service logback-spring.xml (no Loki appender)

**File:** `nexus-ai-assistant-service/src/main/resources/logback-spring.xml`  
**Agent label:** `fix:ai-assistant-logback`

**Problem:** Plain text console appender only. No Loki push. No profile separation. No structured JSON for docker.

**Action:** Same standardized rewrite as Fix 12. Logger targets `com.nexus.ai`.

---

### Fix 14 — nexus-ai-kyc-service logback-spring.xml (no Loki appender)

**File:** `nexus-ai-kyc-service/src/main/resources/logback-spring.xml`  
**Agent label:** `fix:ai-kyc-logback`

**Problem:** Single `STDOUT` appender with no profile split, no Loki push, no structured JSON.

**Action:** Rewritten with `!docker,!production` / `docker,production` split, Loki4j async appender (queueSize 4096), LogstashEncoder. Logger targets `com.nexus.kyc`, suppresses `com.amazonaws` noise at WARN.

---

### Fix 15 — nexus-discovery-service logback-spring.xml (no Loki appender)

**File:** `nexus-discovery-service/src/main/resources/logback-spring.xml`  
**Agent label:** `fix:discovery-logback`

**Problem:** Used `LogstashEncoder` for JSON but had no Loki4j push appender. Discovery service logs never reached Loki.

**Action:** Rewritten with profile split. Dev profile uses plain pattern encoder. Docker/production profile adds Loki4j async (queueSize 2048 — lower than services since discovery is lower throughput). Eureka loggers set to WARN in docker to reduce noise.

---

### Fix 16 — nexus-identity-service logback-spring.xml (Loki not profile-gated)

**File:** `nexus-identity-service/src/main/resources/logback-spring.xml`  
**Agent label:** `fix:identity-logback`

**Problem:** The Loki4j appender was declared **outside** any `<springProfile>` block, meaning it fired in ALL environments including local development. When Loki is not running locally, every log write produces a connection refused error, which Logback reports as an error — flooding the console and potentially blocking application threads despite `neverBlock: true`.

**Action:** Wrapped entire appender config in proper `springProfile` blocks:
- `!docker,!production`: plain pattern console only (no Loki connection attempted)
- `docker,production`: LogstashEncoder console + Loki4j async (queueSize 10000 — kept at the existing value since identity service is high-throughput)

---

### Fix 17 — nexus-saga-orchestrator logback-spring.xml (standardize)

**File:** `nexus-saga-orchestrator/src/main/resources/logback-spring.xml`  
**Agent label:** `fix:saga-logback`

**Problem:** The logback file used generic `com.nexus` logger name (instead of `com.nexus.saga`), was missing Eureka noise suppression, missing Kafka logging in dev profile, and had queueSize 1024 (too small).

**Action:** Rewritten with:
- Logger `com.nexus.saga` (service-specific)
- Dev: adds `org.springframework.kafka: DEBUG` for saga event tracing
- Docker/production: suppresses `com.netflix.eureka`, `org.apache.kafka` at WARN, queueSize 4096

---

### Fix 18 — GitHub Actions workflows (7 services missing CI)

**Files created (7):**
```
nexus-fraud-service/.github/workflows/ci.yml
nexus-ledger-service/.github/workflows/ci.yml
nexus-notification-service/.github/workflows/ci.yml
nexus-ai-assistant-service/.github/workflows/ci.yml
nexus-ai-kyc-service/.github/workflows/ci.yml
nexus-risk-scoring-service/.github/workflows/ci.yml
nexus-audit-query-jvm/.github/workflows/ci.yml
```
**Agent label:** `fix:github-workflows`

**Problem:** These 7 services had no CI pipeline. Any PR to them would have no automated build/test verification.

**Action:** Read the reference workflow from `nexus-account-service/.github/workflows/` then created matching `ci.yml` for each missing service. Each workflow:
- Triggers on `push` to `main` and `pull_request`
- Sets up Java (Temurin)
- Runs `mvn package -DskipTests` (build)
- Runs `mvn test` (unit tests)
- Builds Docker image with service-specific tag

---

## Architecture Report Key Findings (reference)

### Port Map (all services)

| Port | Service |
|---|---|
| 3000 | nexus-grafana |
| 3100 | nexus-loki |
| 4566 | nexus-localstack (dev AWS emulation) |
| 5432 | nexus-postgres |
| 6379 | nexus-redis |
| 8080 | nexus-api-gateway |
| 8083 | nexus-identity-service |
| 8085 | nexus-account-service |
| 8086 | nexus-transaction-service |
| 8087 | nexus-fraud-service |
| 8088 | nexus-ledger-service |
| 8089 | nexus-notification-service |
| 8090 | nexus-ai-assistant-service |
| 8091 | nexus-ai-kyc-service |
| 8092 | nexus-analytics-service |
| 8094 | nexus-risk-scoring-service |
| 8095 | nexus-saga-orchestrator |
| 8096 | audit-write-native (Quarkus) |
| 8097 | nexus-audit-query-jvm |
| 8190 | nexus-kafka-ui (was 8090 — fixed) |
| 8761 | nexus-discovery-service |
| 8888 | nexus-config-service |
| 9090 | nexus-prometheus |
| 9200 | nexus-elasticsearch |
| 9411 | nexus-zipkin |
| 11434 | nexus-ollama |
| 19092 | nexus-kafka external (localhost, was 9092 — fixed) |
| 27017 | nexus-mongodb |

### Standard config blocks applied across all services

**Spring Cloud Config client (docker profile):**
```yaml
spring.cloud.config:
  uri: http://nexus-config-service:8888
  enabled: true
  fail-fast: true
  username: ${CONFIG_SERVER_USERNAME:nexus-config}
  password: ${CONFIG_SERVER_PASSWORD:nexus-config-password}
  retry: { max-attempts: 10, initial-interval: 2000, max-interval: 10000, multiplier: 1.5 }
spring.cloud.bus.enabled: true
```

**Eureka client (docker profile):**
```yaml
eureka:
  client:
    service-url.defaultZone: http://nexus-discovery-service:8761/eureka/
    registry-fetch-interval-seconds: 30
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 30
    lease-expiration-duration-in-seconds: 90
    health-check-url-path: /actuator/health
```

**Loki4j appender (logback, docker/production profile):**
```xml
<appender name="LOKI" class="com.github.loki4j.logback.Loki4jAppender">
    <http><url>${lokiUrl}</url></http>
    <format>
        <label><pattern>service=${appName},environment=${environment},level=%level</pattern></label>
        <message class="com.github.loki4j.logback.JsonLayout"/>
    </format>
    <neverBlock>true</neverBlock>
</appender>
<appender name="ASYNC_LOKI" class="ch.qos.logback.classic.AsyncAppender">
    <appender-ref ref="LOKI"/>
    <neverBlock>true</neverBlock>
    <queueSize>4096</queueSize>
</appender>
```

---

## Remaining Items (not yet fixed)

These were identified during the audit but deferred for a second pass:

| # | Item | Risk | Files affected |
|---|---|---|---|
| 1 | Kafka + Elasticsearch have no Prometheus metrics exporters | Broker and ES cluster metrics invisible to Prometheus/Grafana | `kafka/docker-compose.yml`, any service-level `observability/prometheus.yml` |
| 2 | No Grafana dashboard JSON files committed | Grafana starts with empty dashboards despite provisioning config | `nexus-*/observability/grafana/provisioning/dashboards/` |
| 3 | PostgreSQL `max_connections` too low | 380 total possible Hikari connections vs default Postgres max 100 — exhaustion at full load | `nexus-*/docker-compose.yml` (Postgres container command args) or platform-config global Hikari defaults |
| 4 | `audit-write-native` (Quarkus) has no Loki push | Quarkus JSON console only; no structured log shipping to Loki | `audit-write-native/src/main/resources/application.properties` |
| 5 | `nexus-platform-config/nexus-audit-service.yml` (wrong name) | File exists but is never consumed — Quarkus does not use Spring Cloud Config | Can be deleted or repurposed |
| 6 | `nexus-discovery-service` has no platform-config dev override | No `nexus-discovery-service-dev.yml` in config repo | `nexus-config-service/nexus-platform-config/` |

---

## Files Modified — Complete List

```
kafka/docker-compose.yml
nexus-discovery-service/src/main/resources/application-dev.yml
nexus-discovery-service/src/main/resources/logback-spring.xml
nexus-fraud-service/src/main/resources/application-docker.yml
nexus-fraud-service/src/main/resources/logback-spring.xml
nexus-fraud-service/.github/workflows/ci.yml                          (CREATED)
nexus-ledger-service/.github/workflows/ci.yml                         (CREATED)
nexus-notification-service/.github/workflows/ci.yml                   (CREATED)
nexus-ai-assistant-service/src/main/resources/logback-spring.xml
nexus-ai-assistant-service/.github/workflows/ci.yml                   (CREATED)
nexus-ai-kyc-service/src/main/resources/logback-spring.xml
nexus-ai-kyc-service/.github/workflows/ci.yml                         (CREATED)
nexus-analytics-service/src/main/resources/application.yml
nexus-risk-scoring-service/src/main/resources/application.yml
nexus-risk-scoring-service/.github/workflows/ci.yml                   (CREATED)
nexus-account-service/src/main/resources/application.yml
nexus-account-service/src/main/resources/application-dev.yml
nexus-transaction-service/src/main/resources/application.yml
nexus-transaction-service/src/main/resources/application-dev.yml
nexus-identity-service/src/main/resources/logback-spring.xml
nexus-saga-orchestrator/src/main/resources/logback-spring.xml
nexus-audit-query-jvm/.github/workflows/ci.yml                        (CREATED)
nexus-config-service/src/main/resources/application.yml
nexus-config-service/nexus-platform-config/nexus-audit-query-jvm.yml  (CREATED)
nexus-config-service/nexus-platform-config/nexus-audit-query-jvm-dev.yml (CREATED)
nexus-config-service/nexus-platform-config/nexus-fraud-service-dev.yml
nexus-config-service/nexus-platform-config/nexus-notification-service.yml
nexus-config-service/nexus-platform-config/nexus-ai-assistant-service-dev.yml
```

**Total:** 29 files touched (22 modified, 9 created)
