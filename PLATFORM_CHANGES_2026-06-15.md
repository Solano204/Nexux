# NEXUS Platform — All Changes Log

**Date:** 2026-06-15
**Branch:** main
**Author:** Carlos Josue Lopez Solano
**Total files changed:** ~137 modified/deleted + 50+ new files created

---

## Summary of change categories

| # | Category | Services affected | Type |
|---|---|---|---|
| 1 | Kafka + full infrastructure in docker-compose | `kafka/` | Modified |
| 2 | Main application class renamed (`Main.java` → `*Application.java`) | 11 services | Renamed/Created |
| 3 | `application-docker.yml` removed from all services | 14 services | Deleted |
| 4 | `pom.xml` build plugin fixes (dependency packaging, compiler) | 12 services | Modified |
| 5 | `application.yml` / `-dev.yml` / `-prod.yml` config cleanup | All services | Modified |
| 6 | `nexus-config-service` config repo full restructure | `nexus-config-service` | Major refactor |
| 7 | `audit-write-native` Quarkus API migration | `audit-write-native` | Refactored |
| 8 | `nexus-api-gateway` filter fixes + logback cleanup | `nexus-api-gateway` | Modified |
| 9 | `nexus-fraud-service` DB migration directory fix | `nexus-fraud-service` | Fixed |
| 10 | `nexus-identity-service` DB migrations + RSA keys | `nexus-identity-service` | Added |
| 11 | `nexus-saga-orchestrator` misplaced logback removed | `nexus-saga-orchestrator` | Cleaned |
| 12 | GitHub Actions CI workflows added (7 services) | 7 services | Created |
| 13 | Postman documentation (14 collections) | All services | Created |

---

## Change 1 — `kafka/docker-compose.yml` (Infrastructure overhaul)

**File:** `kafka/docker-compose.yml`

### What changed

The docker-compose now contains the FULL infrastructure stack for the NEXUS platform, not just Kafka.

**Added services:**

| Service | Image | Port | Purpose |
|---|---|---|---|
| `nexus-postgres` | `pgvector/pgvector:pg16` | `5432` | Main relational DB for all Spring Boot services. Uses pgvector image (plain postgres:16 does NOT have the vector extension needed by account, fraud, ledger, ai-assistant, and audit-query services). |
| `nexus-mongodb` | `mongo:7` | `27017` | Document store for fraud decisions, compliance alerts, audit logs |
| `nexus-grafana` | `grafana/grafana` | `3000` | Metrics/log dashboard |
| `nexus-prometheus` | `prom/prometheus` | `9090` | Metrics scraping |
| `nexus-loki` | `grafana/loki` | `3100` | Log aggregation backend |
| `nexus-zipkin` | `openzipkin/zipkin` | `9411` | Distributed tracing |

**Added Docker volumes:**
```
postgres-data
mongodb-data
grafana-data
```

**PostgreSQL init:** `kafka/init/01_create_databases.sh` creates all 10 databases on first boot:
```
nexus_identity, nexus_accounts, nexus_transactions, nexus_fraud,
nexus_ledger, nexus_kyc, nexus_ai_assistant, nexus_risk, nexus_saga, nexus_audit
```
pgvector extension enabled in: `nexus_accounts`, `nexus_fraud`, `nexus_ledger`, `nexus_ai_assistant`, `nexus_audit`

### Kafka listener fix (critical)

**Before (broken):** containers in the Docker network could not connect to Kafka because `nexus-kafka:29092` was advertised but ALL services use `nexus-kafka:9092`.

| Setting | Before | After |
|---|---|---|
| `ports` | `9092:9092` | `9092:9092` (internal) + `19092:19092` (external/dev) |
| `KAFKA_LISTENERS` | `PLAINTEXT://nexus-kafka:29092,...` | `PLAINTEXT_INTERNAL://0.0.0.0:9092,PLAINTEXT_EXTERNAL://0.0.0.0:19092,...` |
| `KAFKA_ADVERTISED_LISTENERS` | `PLAINTEXT://nexus-kafka:29092` | `PLAINTEXT_INTERNAL://nexus-kafka:9092,PLAINTEXT_EXTERNAL://localhost:19092` |
| `KAFKA_INTER_BROKER_LISTENER_NAME` | `PLAINTEXT` | `PLAINTEXT_INTERNAL` |
| `KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS` (UI) | `nexus-kafka:29092` | `nexus-kafka:9092` |
| Healthcheck bootstrap | `localhost:9092` | `localhost:19092` |
| Kafka UI port | `8090:8080` (conflict!) | `8190:8080` |
| Topic creation `--bootstrap-server` (30 topics) | `nexus-kafka:29092` | `nexus-kafka:9092` |

**Result:** Containers reach broker at `nexus-kafka:9092`. External dev tools (e.g. kafkacat) reach it at `localhost:19092`. Kafka UI no longer conflicts with `nexus-ai-assistant-service` (also port 8090).

---

## Change 2 — Main application class renames

All services that had a generic `Main.java` have been renamed to the correct Spring Boot convention (`*Application.java`). This is required for Spring Boot's autoconfiguration scan to work correctly.

| Service | Old class | New class | Status |
|---|---|---|---|
| `nexus-fraud-service` | `Main.java` | `FraudApplication.java` | Renamed (staged) |
| `nexus-identity-service` | `Main.java` | `NexusIdentityServiceApplication.java` | Renamed (staged) |
| `nexus-ledger-service` | `Main.java` | `LedgerApplication.java` | Renamed (staged) |
| `nexus-transaction-service` | `Main.java` | `TransactionApplication.java` | Renamed (staged) |
| `nexus-ai-assistant-service` | `Main.java` deleted | `AiAssistantApplication.java` | Created (untracked) |
| `nexus-ai-kyc-service` | `Main.java` deleted | `AiKycApplication.java` | Created (untracked) |
| `nexus-analytics-service` | `Main.java` deleted | `AnalyticsApplication.java` | Created (untracked) |
| `nexus-audit-query-jvm` | `Main.java` deleted | `AuditQueryApplication.java` | Created (untracked) |
| `nexus-notification-service` | `Main.java` deleted | `NotificationApplication.java` | Created (untracked) |
| `nexus-risk-scoring-service` | `Main.java` deleted | `RiskScoringApplication.java` | Created (untracked) |
| `nexus-saga-orchestrator` | `Main.java` deleted | `SagaOrchestratorApplication.java` | Created (untracked) |
| `nexus-identity-service` | — | `identity/NexusIdentityServiceApplication.java` | Created (untracked — correct package) |

---

## Change 3 — `application-docker.yml` removed (14 services)

These files were duplicating configuration already managed by `nexus-config-service` for the Docker profile. Keeping both caused conflicts where the local file silently overrode the config server values.

**Deleted files:**
```
nexus-account-service/src/main/resources/application-docker.yml
nexus-ai-assistant-service/src/main/resources/application-docker.yml
nexus-ai-kyc-service/src/main/resources/application-docker.yml
nexus-analytics-service/src/main/resources/application-docker.yml
nexus-api-gateway/src/main/resources/application-docker.yml
nexus-audit-query-jvm/src/main/resources/application-docker.yml
nexus-config-service/src/main/resources/application-docker.yml
nexus-discovery-service/src/main/resources/application-docker.yml
nexus-fraud-service/src/main/resources/application-docker.yml
nexus-identity-service/src/main/resources/application-docker.yml
nexus-ledger-service/src/main/resources/application-docker.yml
nexus-notification-service/src/main/resources/application-docker.yml
nexus-risk-scoring-service/src/main/resources/application-docker.yml
nexus-saga-orchestrator/src/main/resources/application-docker.yml
nexus-transaction-service/src/main/resources/application-docker.yml
```

Docker-specific overrides now live exclusively in `nexus-config-service/nexus-platform-config/nexus-*-service-prod.yml`.

---

## Change 4 — `pom.xml` build plugin fixes (12 services)

### `maven-dependency-plugin` added (all affected services)

Added to: `nexus-account-service`, `nexus-ai-assistant-service`, `nexus-ai-kyc-service`, `nexus-analytics-service`, `nexus-audit-query-jvm`, `nexus-fraud-service`, `nexus-ledger-service`, `nexus-notification-service`, `nexus-risk-scoring-service`, `nexus-saga-orchestrator`, `nexus-transaction-service`

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <id>copy-dependencies</id>
            <phase>package</phase>
            <goals><goal>copy-dependencies</goal></goals>
            <configuration>
                <outputDirectory>${project.build.directory}/libs</outputDirectory>
                <includeScope>runtime</includeScope>
            </configuration>
        </execution>
    </executions>
</plugin>
```

This copies all runtime jars to `target/libs/` during `mvn package`, which is needed for Docker COPY layer optimization (Dockerfile COPY target/libs/ pattern).

### `maven-compiler-plugin` configuration fixed (`nexus-fraud-service`)

Before: `enablePreview` was declared twice (once in a duplicate plugin block added by mistake), causing a compiler warning about duplicate plugin declarations.

After: Single compiler plugin block with Java 25 + `--enable-preview` + Lombok annotation processor.

### `nexus-audit-query-jvm` — explicit `spring-boot-maven-plugin` `mainClass` set

```xml
<mainClass>com.nexus.audit.query.AuditQueryApplication</mainClass>
```

Previously inherited from root pom but was resolving to wrong class after the `Main.java` rename.

### `audit-write-native/pom.xml` — added `quarkus-logging-json`

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-logging-json</artifactId>
</dependency>
```

Required for structured JSON logging in Docker/production (`%docker.quarkus.log.console.json=true`).

---

## Change 5 — Application configuration cleanup (all services)

### Pattern applied to every service

`application.yml` (base) — trimmed down. Docker-specific blocks removed (now served by config service). Only universal defaults kept.

`application-dev.yml` — updated localhost connection strings to match the new infrastructure:
- PostgreSQL: `localhost:5432` / `nexus_dev_password`
- MongoDB: `localhost:27017`
- Redis: `localhost:6379`
- Kafka: `localhost:19092` (external port — matches new docker-compose)
- Elasticsearch: `localhost:9200`

`application-prod.yml` — updated with proper env-var references for all connection strings. Removed hardcoded passwords. Tracing probability `0.1` (was `1.0` in some services).

### Notable per-service changes

**`nexus-account-service`** — DB name corrected: `nexus_account` → `nexus_accounts` (plural, matches production platform-config).

**`nexus-transaction-service`** — DB name corrected: `nexus_transaction` → `nexus_transactions`.

**`nexus-analytics-service`** — Tracing probability corrected in base `application.yml`: `0.1` → `1.0` (was giving sparse traces in dev).

**`nexus-risk-scoring-service`** — Same tracing fix as analytics: `0.1` → `1.0` in base config.

**`nexus-discovery-service`** — `application-dev.yml` replaced completely. Old file had fraud-service production config accidentally pasted in (port 8087, Kafka bootstrap, Eureka client). Now correctly configured as a standalone Eureka server on port 8761.

**`nexus-api-gateway`** — `logback-spring.xml` deleted from root (`nexus-api-gateway/logback-spring.xml`) — it was misplaced. Correct file is at `src/main/resources/logback-spring.xml`.

**`nexus-saga-orchestrator`** — `logback-spring.xml` deleted from root (`nexus-saga-orchestrator/logback-spring.xml`) — same misplacement issue as gateway. Correct file is in `src/main/resources/`.

---

## Change 6 — `nexus-config-service` config repo restructure

**Directory:** `nexus-config-service/nexus-platform-config/`

### Files deleted (old format, single file per service)

```
application.yml                            (was a global fallback — replaced by per-env files)
nexus-account-service.yml
nexus-ai-assistant-service.yml
nexus-ai-kyc-service.yml
nexus-analytics-service.yml
nexus-api-gateway.yml
nexus-audit-service.yml
nexus-discovery-service.yml
nexus-fraud-service.yml
nexus-identity-service.yml
nexus-ledger-service.yml
nexus-notification-service.yml
nexus-risk-scoring-service.yml
nexus-saga-orchestrator.yml
nexus-transaction-service.yml
```

### Files created (new per-environment format)

Spring Cloud Config resolves: `{application}-{profile}.yml`. Files now split by environment:

```
nexus-account-service-prod.yml
nexus-ai-assistant-service-prod.yml
nexus-ai-kyc-service-prod.yml
nexus-analytics-service-prod.yml
nexus-api-gateway-prod.yml
nexus-audit-query-jvm-prod.yml
nexus-audit-service-prod.yml
nexus-discovery-service-prod.yml
nexus-fraud-service-prod.yml
nexus-identity-service-prod.yml
nexus-ledger-service-prod.yml
nexus-notification-service-prod.yml
nexus-risk-scoring-service-prod.yml
nexus-saga-orchestrator-prod.yml
nexus-transaction-service-prod.yml
```

### Files updated (`-dev.yml` variants)

All existing `*-dev.yml` files updated to use `localhost` connection strings and match new infrastructure ports. Key changes:
- `nexus-fraud-service-dev.yml` — fixed duplicate YAML `spring.data:` key (was silently dropping MongoDB or Redis config)
- `nexus-ai-kyc-service-dev.yml` — added pgvector dev overrides
- `nexus-ai-assistant-service-dev.yml` — added Redis + pgvector dev overrides
- `nexus-notification-service-dev.yml` (via `-dev.yml`) — added Redis block (was missing entirely in production file)

### `nexus-config-service/src/main/resources/application.yml`

`GIT_TOKEN` default removed:
```yaml
# Before
password: ${GIT_TOKEN:your-token-here}

# After
password: ${GIT_TOKEN}
```
Service now fails fast at startup if `GIT_TOKEN` is not set, instead of silently starting with a broken Git auth.

---

## Change 7 — `audit-write-native` Quarkus API migration

### `ElasticsearchConfig.java` deleted

`audit-write-native/src/main/java/com/nexus/audit/write/config/ElasticsearchConfig.java`

Elasticsearch client is now configured entirely through `application.properties` using Quarkus's built-in `quarkus.elasticsearch.*` properties. The manual `@Produces` CDI bean is no longer needed (was actually conflicting with Quarkus auto-configuration).

### `ComplianceRuleEvaluator.java` refactored

Four API migration bugs fixed:

| Component | Before | After | Reason |
|---|---|---|---|
| Redis client | `ReactiveRedisClient` (deprecated) | `ReactiveRedisDataSource` | New Quarkus Redis API since 3.x |
| Redis TTL | On `ReactiveValueCommands` (wrong) | On `ReactiveKeyCommands` | `expire()` only exists on key commands, not value commands |
| MongoDB client | `com.mongodb.reactivestreams.client.MongoClient` (not a CDI bean) | `io.quarkus.mongodb.reactive.ReactiveMongoClient` (CDI bean) | Quarkus's MongoDB extension exposes `ReactiveMongoClient` as CDI, not the raw driver |
| Reactive bridge | `FlowAdapters` wrapping | Direct `Uni` chain | `ReactiveMongoClient` returns `Uni` directly — no bridge needed |

Added `@PostConstruct` to initialize `ReactiveValueCommands` and `ReactiveKeyCommands` from the injected `ReactiveRedisDataSource`.

### `application.properties` — comments added

Added inline Spanish comments explaining which lines to delete when switching between profiles (temporary development notes during profile testing).

---

## Change 8 — `nexus-api-gateway` fixes

### `JwtAuthenticationFilter.java`

Added missing `name()` override:

```java
@Override
public String name() {
    return "JwtAuthentication";
}
```

Spring Cloud Gateway requires `GatewayFilterFactory` implementations to expose a name for route config (`filters: [JwtAuthentication]`). Without it, the filter can't be referenced by name in YAML route config.

### `WebhookHmacFilter.java`

Minor: HMAC validation path check updated to match new webhook route prefix.

### `IpKeyResolver.java`

Minor: null-safe IP extraction for rate limiting when `X-Forwarded-For` header is absent.

---

## Change 9 — `nexus-fraud-service` DB migration directory fix

**Problem:** Flyway migrations were in the wrong directory.

```
Before: src/main/resources/db.migration/   ← Flyway NEVER finds this
After:  src/main/resources/db/migration/   ← Flyway default location
```

**Files deleted (wrong location):**
```
nexus-fraud-service/src/main/resources/db.migration/V1__create_fraud_decisions.sql
nexus-fraud-service/src/main/resources/db.migration/V2__create_outbox.sql
```

**New location (untracked — staged for add):**
```
nexus-fraud-service/src/main/resources/db/migration/
```

Impact: In production, Flyway was silently skipping migrations because `db.migration` is not a recognized path. The fraud tables were never being created by Flyway (they may have been created manually).

---

## Change 10 — `nexus-identity-service` DB migrations + RSA keys

### New migrations added

```
V7__fix_audit_log_ip_address.sql   — Fixes ip_address column type in audit_log table
V8__fix_sessions_ip_address.sql    — Fixes ip_address column type in sessions table
```

These fix a column type mismatch where `ip_address` was `VARCHAR(45)` but IPv6 addresses can be up to 45 chars and the Spring type mapping expected `inet`.

### `V4__create_audit_log.sql` updated

Minor column definition update aligned with updated application entity.

### `keys/` directory created

```
nexus-identity-service/keys/
```

Contains RSA key pair for JWT signing (dev environment). These are the `private.pem` / `public.pem` files referenced by `application-dev.yml`. Previously had to be generated manually before the service would start.

### `NexusIdentityServiceApplication.java` (correct package)

New file at `src/main/java/com/nexus/identity/NexusIdentityServiceApplication.java` (correct `identity` sub-package). The previously staged rename target was at `com.nexus` root package — this is the correct location.

---

## Change 11 — `nexus-saga-orchestrator` cleanup

### `V4__create_saga_timeouts.sql` simplified

Removed excessive indexing and constraints that were causing Flyway validation failures on clean installs. The table definition is now minimal and matches the JPA entity.

### Misplaced `logback-spring.xml` deleted

`nexus-saga-orchestrator/logback-spring.xml` (root directory) removed. The correct file is at `src/main/resources/logback-spring.xml`. Spring Boot only loads `logback-spring.xml` from the classpath (resources), not the project root.

---

## Change 12 — GitHub Actions CI workflows (7 services)

Added `ci.yml` for services that had no CI pipeline:

```
nexus-fraud-service/.github/workflows/ci.yml
nexus-ledger-service/.github/workflows/ci.yml
nexus-notification-service/.github/workflows/ci.yml
nexus-ai-assistant-service/.github/workflows/ci.yml
nexus-ai-kyc-service/.github/workflows/ci.yml
nexus-risk-scoring-service/.github/workflows/ci.yml
nexus-audit-query-jvm/.github/workflows/ci.yml
```

Each workflow:
- Triggers on `push` to `main` and on `pull_request`
- Sets up Java 25 (Temurin)
- Runs `mvn package -DskipTests` (build verification)
- Runs `mvn test` (unit tests)
- Builds Docker image with service-specific tag

---

## Change 13 — Postman Documentation (14 collections)

**Folder created:** `DOCUMENTATION-POSTMAN/`

One Postman Collection v2.1 JSON file per service, directly importable into Postman:

| File | Service | Port | Endpoints documented |
|---|---|---|---|
| `nexus-account-service.postman_collection.json` | Account Service | 8085 | 17 endpoints (public + internal) |
| `nexus-ai-assistant-service.postman_collection.json` | AI Assistant | 8090 | 4 endpoints (chat SSE + doc analysis) |
| `nexus-ai-kyc-service.postman_collection.json` | AI KYC | 8091 | 12 endpoints (public + internal) |
| `nexus-analytics-service.postman_collection.json` | Analytics | 8092 | 7 endpoints (analytics + insights + internal) |
| `nexus-api-gateway.postman_collection.json` | API Gateway | 8080 | 12 endpoints (fallbacks + gateway routes) |
| `nexus-audit-query-jvm.postman_collection.json` | Audit Query | 8097 | 8 endpoints (events + compliance) |
| `nexus-fraud-service.postman_collection.json` | Fraud Service | 8087 | 14 endpoints (internal fraud API) |
| `nexus-identity-service.postman_collection.json` | Identity | 8083 | 13 endpoints (auth + user + KYC) |
| `nexus-ledger-service.postman_collection.json` | Ledger | 8088 | 10 endpoints (public + internal) |
| `nexus-notification-service.postman_collection.json` | Notifications | 8089 | 7 endpoints (notifications + preferences) |
| `nexus-risk-scoring-service.postman_collection.json` | Risk Scoring | 8094 | 7 endpoints (internal risk API) |
| `nexus-saga-orchestrator.postman_collection.json` | Saga Orchestrator | 8095 | 5 endpoints (internal saga state) |
| `nexus-transaction-service.postman_collection.json` | Transactions | 8086 | 8 endpoints (public + internal) |
| `audit-write-native.postman_collection.json` | Audit Write (Quarkus) | 8096 | 20 entries (health + 15 Kafka schemas + ES/MongoDB verify) |

All collections include:
- Collection variables for `baseUrl`, `accessToken`, `userId`, etc.
- Full request/response body documentation in descriptions
- Realistic sample data (UUIDs, amounts in MXN, Mexican formats)
- Internal endpoints documented with required headers (`X-Internal-Service`, `X-User-Id`)

---

## Other files in project root (untracked)

| File | Description |
|---|---|
| `ALL_CHANGES.md` | Config audit log from 2026-06-13 (18 fixes applied) |
| `HOW_TO_RUN_LOCAL.md` | Step-by-step guide to run all services locally |
| `INDEPENDENT_SERVICES.md` | Which services can run independently (no Eureka/Config required) |
| `TUTORIAL_RUN_ALL_SERVICES_INFRAESTRUCTURE.md` | Full infrastructure startup tutorial |
| `kafka/init/` | PostgreSQL init scripts for database creation |
| `kafka/prometheus.yml` | Prometheus scrape config for all services |

---

## Complete file change list

### Staged (already `git add`-ed)

```
DELETED   nexus-api-gateway/logback-spring.xml
RENAMED   nexus-fraud-service/src/main/java/com/nexus/fraud/Main.java → FraudApplication.java
RENAMED   nexus-identity-service/src/main/java/com/nexus/Main.java → NexusIdentityServiceApplication.java
RENAMED   nexus-ledger-service/src/main/java/com/nexus/ledger/Main.java → LedgerApplication.java
RENAMED   nexus-transaction-service/src/main/java/com/nexus/transaction/Main.java → TransactionApplication.java
```

### Modified (not yet staged)

```
audit-write-native/pom.xml
audit-write-native/src/main/java/com/nexus/audit/write/consumer/ComplianceRuleEvaluator.java
audit-write-native/src/main/resources/application.properties
kafka/docker-compose.yml
nexus-account-service/pom.xml
nexus-account-service/src/main/resources/application-dev.yml
nexus-account-service/src/main/resources/application-prod.yml
nexus-account-service/src/main/resources/application.yml
nexus-ai-assistant-service/pom.xml
nexus-ai-assistant-service/src/main/resources/application-dev.yml
nexus-ai-assistant-service/src/main/resources/application-prod.yml
nexus-ai-assistant-service/src/main/resources/application.yml
nexus-ai-assistant-service/src/main/resources/logback-spring.xml
nexus-ai-kyc-service/pom.xml
nexus-ai-kyc-service/src/main/java/com/nexus/kyc/infrastructure/jpa/KycAuditEntryJPA.java
nexus-ai-kyc-service/src/main/resources/application-dev.yml
nexus-ai-kyc-service/src/main/resources/application-prod.yml
nexus-ai-kyc-service/src/main/resources/application.yml
nexus-ai-kyc-service/src/main/resources/logback-spring.xml
nexus-analytics-service/pom.xml
nexus-analytics-service/src/main/resources/application-dev.yml
nexus-analytics-service/src/main/resources/application-prod.yml
nexus-analytics-service/src/main/resources/application.yml
nexus-api-gateway/pom.xml
nexus-api-gateway/src/main/java/com/nexus/gateway/filter/JwtAuthenticationFilter.java
nexus-api-gateway/src/main/java/com/nexus/gateway/filter/WebhookHmacFilter.java
nexus-api-gateway/src/main/java/com/nexus/gateway/ratelimit/IpKeyResolver.java
nexus-api-gateway/src/main/resources/application-prod.yml
nexus-api-gateway/src/main/resources/logback-spring.xml
nexus-audit-query-jvm/pom.xml
nexus-audit-query-jvm/src/main/resources/application-dev.yml
nexus-audit-query-jvm/src/main/resources/application-prod.yml
nexus-config-service/nexus-platform-config/application-dev.yml
nexus-config-service/nexus-platform-config/application-prod.yml
nexus-config-service/nexus-platform-config/nexus-account-service-dev.yml
nexus-config-service/nexus-platform-config/nexus-ai-assistant-service-dev.yml
nexus-config-service/nexus-platform-config/nexus-ai-kyc-service-dev.yml
nexus-config-service/nexus-platform-config/nexus-audit-query-jvm-dev.yml
nexus-config-service/nexus-platform-config/nexus-audit-service-dev.yml
nexus-config-service/nexus-platform-config/nexus-fraud-service-dev.yml
nexus-config-service/nexus-platform-config/nexus-identity-service-dev.yml
nexus-config-service/nexus-platform-config/nexus-ledger-service-dev.yml
nexus-config-service/nexus-platform-config/nexus-notification-service-dev.yml
nexus-config-service/nexus-platform-config/nexus-risk-scoring-service-dev.yml
nexus-config-service/nexus-platform-config/nexus-saga-orchestrator-dev.yml
nexus-config-service/nexus-platform-config/nexus-transaction-service-dev.yml
nexus-config-service/src/main/resources/application-prod.yml
nexus-config-service/src/main/resources/application.yml
nexus-discovery-service/pom.xml
nexus-discovery-service/src/main/java/com/nexus/discovery/Main.java
nexus-discovery-service/src/main/resources/application-dev.yml
nexus-discovery-service/src/main/resources/application-prod.yml
nexus-discovery-service/src/main/resources/application.yml
nexus-discovery-service/src/main/resources/logback-spring.xml
nexus-fraud-service/pom.xml
nexus-fraud-service/src/main/java/com/nexus/fraud/FraudApplication.java
nexus-fraud-service/src/main/resources/application-dev.yml
nexus-fraud-service/src/main/resources/application-prod.yml
nexus-fraud-service/src/main/resources/application.yml
nexus-fraud-service/src/main/resources/logback-spring.xml
nexus-identity-service/src/main/resources/application-dev.yml
nexus-identity-service/src/main/resources/application-prod.yml
nexus-identity-service/src/main/resources/application.yml
nexus-identity-service/src/main/resources/db/migration/V4__create_audit_log.sql
nexus-identity-service/src/main/resources/logback-spring.xml
nexus-ledger-service/pom.xml
nexus-ledger-service/src/main/java/com/nexus/ledger/LedgerApplication.java
nexus-ledger-service/src/main/resources/application-dev.yml
nexus-ledger-service/src/main/resources/application-prod.yml
nexus-ledger-service/src/main/resources/application.yml
nexus-notification-service/pom.xml
nexus-notification-service/src/main/resources/application-dev.yml
nexus-notification-service/src/main/resources/application-prod.yml
nexus-notification-service/src/main/resources/application.yml
nexus-risk-scoring-service/pom.xml
nexus-risk-scoring-service/src/main/resources/application-dev.yml
nexus-risk-scoring-service/src/main/resources/application-prod.yml
nexus-risk-scoring-service/src/main/resources/application.yml
nexus-risk-scoring-service/src/main/resources/db/migration/V1__create_risk_profiles.sql
nexus-saga-orchestrator/pom.xml
nexus-saga-orchestrator/src/main/resources/application-dev.yml
nexus-saga-orchestrator/src/main/resources/application-prod.yml
nexus-saga-orchestrator/src/main/resources/application.yml
nexus-saga-orchestrator/src/main/resources/db/migration/V4__create_saga_timeouts.sql
nexus-saga-orchestrator/src/main/resources/logback-spring.xml
nexus-transaction-service/pom.xml
nexus-transaction-service/src/main/java/com/nexus/transaction/TransactionApplication.java
nexus-transaction-service/src/main/resources/application-dev.yml
nexus-transaction-service/src/main/resources/application-prod.yml
nexus-transaction-service/src/main/resources/application.yml
pom.xml
```

### Deleted (not yet staged)

```
audit-write-native/src/main/java/com/nexus/audit/write/config/ElasticsearchConfig.java
nexus-account-service/src/main/resources/application-docker.yml
nexus-ai-assistant-service/src/main/java/com/nexus/assistant/Main.java
nexus-ai-assistant-service/src/main/resources/application-docker.yml
nexus-ai-kyc-service/src/main/java/com/nexus/kyc/Main.java
nexus-ai-kyc-service/src/main/resources/application-docker.yml
nexus-analytics-service/src/main/java/com/nexus/analytics/Main.java
nexus-analytics-service/src/main/resources/application-docker.yml
nexus-api-gateway/src/main/resources/application-docker.yml
nexus-audit-query-jvm/src/main/java/com/nexus/audit/query/Main.java
nexus-audit-query-jvm/src/main/resources/application-docker.yml
nexus-config-service/nexus-platform-config/application.yml
nexus-config-service/nexus-platform-config/nexus-account-service.yml
nexus-config-service/nexus-platform-config/nexus-ai-assistant-service.yml
nexus-config-service/nexus-platform-config/nexus-ai-kyc-service.yml
nexus-config-service/nexus-platform-config/nexus-analytics-service.yml
nexus-config-service/nexus-platform-config/nexus-api-gateway.yml
nexus-config-service/nexus-platform-config/nexus-audit-service.yml
nexus-config-service/nexus-platform-config/nexus-discovery-service.yml
nexus-config-service/nexus-platform-config/nexus-fraud-service.yml
nexus-config-service/nexus-platform-config/nexus-identity-service.yml
nexus-config-service/nexus-platform-config/nexus-ledger-service.yml
nexus-config-service/nexus-platform-config/nexus-notification-service.yml
nexus-config-service/nexus-platform-config/nexus-risk-scoring-service.yml
nexus-config-service/nexus-platform-config/nexus-saga-orchestrator.yml
nexus-config-service/nexus-platform-config/nexus-transaction-service.yml
nexus-config-service/src/main/resources/application-docker.yml
nexus-discovery-service/src/main/resources/application-docker.yml
nexus-fraud-service/src/main/java/com/nexus/fraud/Main.java
nexus-fraud-service/src/main/resources/application-docker.yml
nexus-fraud-service/src/main/resources/db.migration/V1__create_fraud_decisions.sql
nexus-fraud-service/src/main/resources/db.migration/V2__create_outbox.sql
nexus-identity-service/src/main/java/com/nexus/Main.java
nexus-identity-service/src/main/resources/application-docker.yml
nexus-ledger-service/src/main/java/com/nexus/ledger/Main.java
nexus-ledger-service/src/main/resources/application-docker.yml
nexus-notification-service/src/main/java/com/nexus/notification/Main.java
nexus-notification-service/src/main/resources/application-docker.yml
nexus-risk-scoring-service/src/main/java/com/nexus/risk/Main.java
nexus-risk-scoring-service/src/main/resources/application-docker.yml
nexus-saga-orchestrator/logback-spring.xml
nexus-saga-orchestrator/src/main/java/com/nexus/saga/Main.java
nexus-saga-orchestrator/src/main/resources/application-docker.yml
nexus-transaction-service/src/main/java/com/nexus/transaction/Main.java
nexus-transaction-service/src/main/resources/application-docker.yml
```

### New files (untracked)

```
ALL_CHANGES.md
DOCUMENTATION-POSTMAN/ (14 files)
HOW_TO_RUN_LOCAL.md
INDEPENDENT_SERVICES.md
PLATFORM_CHANGES_2026-06-15.md   ← this file
TUTORIAL_RUN_ALL_SERVICES_INFRAESTRUCTURE.md
kafka/init/
kafka/prometheus.yml
nexus-ai-assistant-service/.github/workflows/ci.yml
nexus-ai-assistant-service/src/main/java/com/nexus/assistant/AiAssistantApplication.java
nexus-ai-kyc-service/.github/workflows/ci.yml
nexus-ai-kyc-service/src/main/java/com/nexus/kyc/AiKycApplication.java
nexus-analytics-service/src/main/java/com/nexus/analytics/AnalyticsApplication.java
nexus-audit-query-jvm/.github/workflows/ci.yml
nexus-audit-query-jvm/src/main/java/com/nexus/audit/query/AuditQueryApplication.java
nexus-config-service/nexus-platform-config/nexus-*-prod.yml (15 files)
nexus-fraud-service/.github/workflows/ci.yml
nexus-fraud-service/src/main/java/com/nexus/ledger/ (misplaced — should be checked)
nexus-fraud-service/src/main/resources/db/
nexus-identity-service/keys/
nexus-identity-service/src/main/java/com/nexus/identity/NexusIdentityServiceApplication.java
nexus-identity-service/src/main/resources/db/migration/V7__fix_audit_log_ip_address.sql
nexus-identity-service/src/main/resources/db/migration/V8__fix_sessions_ip_address.sql
nexus-ledger-service/.github/workflows/ci.yml
nexus-notification-service/.github/workflows/ci.yml
nexus-notification-service/src/main/java/com/nexus/notification/NotificationApplication.java
nexus-risk-scoring-service/.github/workflows/ci.yml
nexus-risk-scoring-service/src/main/java/com/nexus/risk/RiskScoringApplication.java
nexus-saga-orchestrator/src/main/java/com/nexus/saga/SagaOrchestratorApplication.java
```
