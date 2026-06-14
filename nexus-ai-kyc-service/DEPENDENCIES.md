# nexus-ai-kyc-service — Complete Dependency & Run Guide

## ⚠️ READ BUGS.md FIRST

Two bugs — both will prevent startup. Fix them first.

---

## 1. What was created / replaced

| Item | Status |
|------|--------|
| `Dockerfile` | **IMPROVED** — added ZGenerational, MaxMetaspaceSize, security.egd; increased heap to 1GB for 10MB vision payloads |
| `logback-spring.xml` | **IMPROVED** — added Loki + ASYNC_LOKI (neverBlock), verificationId/documentType/kycStatus/stage MDC, production profile |
| `.github/workflows/nexus-ai-kyc-service.yml` | **NEW** — no CI existed |
| `application-dev.yml` | **NEW** — was missing |
| `application-production.yml` | **NEW** — was missing |
| `scripts/postgres-init.sql` | **NEW** |
| `scripts/mongo-init.js` | **NEW** — kyc_documents (7-year TTL) + GridFS indexes |
| `scripts/localstack-init.sh` | **NEW** — S3 bucket setup |

---

## 2. What you need installed

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | **25** | `--enable-preview`, virtual threads |
| Maven | 3.9+ | Build tool |
| Docker Desktop | 24+ with Compose v2 | All infrastructure |

---

## 3. Runtime dependencies

### 3.1 Always required at startup

| Service | Port | Why |
|---------|------|-----|
| **PostgreSQL 16** | 5432 | Immutable KYC audit trail (`kyc_audit_entries`) — plain postgres, no pgvector |
| **MongoDB 7** | 27017 | `kyc_documents` collection + GridFS (encrypted binary document storage) |
| **Kafka** | 9092 | `KycEventProducer` publishes to `identity.kyc.events` and `saga.replies` |
| **LocalStack / real AWS S3** | 4566 (local) | Document retrieval — `DocumentStorageService` downloads from S3 |
| **nexus-config-service** | 8888 | `fail-fast: true` |
| **nexus-discovery-service** | 8761 | Service registration |

### 3.2 Required for AI pipeline

| Service | What breaks |
|---------|------------|
| **OpenAI API** (gpt-4o) | Stage 1 vision extraction fails — pipeline aborted. No fallback. |
| **OpenAI API** (gpt-4o-mini) | Stage 2 comparison fails — even if Stage 1 succeeded. |

**Important:** This service uses `gpt-4o` for vision (Stage 1), not `gpt-4o-mini`. `gpt-4o` is 5-10x more expensive per token. The `HardRuleValidator` runs before any AI call to reject obviously bad documents early and reduce costs.

### 3.3 SAGA integration

This service is called by `nexus-identity-service` during the `OnboardingFlowSaga`:
1. Identity service uploads document to S3
2. Identity service sends `KYC_VERIFY` saga command (via Kafka or direct HTTP)
3. This service processes the pipeline
4. Publishes `KYC_VERIFIED` or `KYC_REJECTED` to `saga.replies`

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

## 5. KYC pipeline (7 steps)

```
Document uploaded to S3 by nexus-identity-service
    ↓
1. DocumentStorageService downloads from S3
2. DocumentQualityValidator — pre-screening (rejects blurry/partial docs)
3. HardRuleValidator        — business rules (size, type, not expired)
4. Stage1DocumentExtraction — GPT-4o Vision → KycExtractedData
5. Stage2DataComparison     — GPT-4o-mini → KycVerificationDecision
6. KycAuditRepository       — immutable audit entry to PostgreSQL
7. KycEventProducer         — publishes decision to Kafka
```

---

## 6. Environment variables

| Variable | Default | Required? |
|----------|---------|-----------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** |
| `DATASOURCE_URL` | `jdbc:postgresql://nexus-postgres:5432/nexus_kyc` | no |
| `POSTGRES_USER` | `nexus` | no |
| `POSTGRES_PASSWORD` | `""` | prod: **YES** |
| `MONGODB_URI` | `mongodb://nexus:nexus_dev@nexus-mongodb:27017/nexus_kyc?authSource=admin` | no |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no |
| `OPENAI_API_KEY` | `""` | **YES** — gpt-4o vision; no fallback |
| `AWS_REGION` | `us-east-1` | no |
| `AWS_ACCESS_KEY_ID` | `""` | **YES** (use `test` for LocalStack) |
| `AWS_SECRET_ACCESS_KEY` | `""` | **YES** (use `test` for LocalStack) |
| `AWS_ENDPOINT_OVERRIDE` | `""` | dev only — `http://nexus-localstack:4566` |
| `KYC_S3_BUCKET` | `nexus-kyc-documents` | no |
| `KYC_ENCRYPTION_ENABLED` | `false` | prod: `true` |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no |
| `TRACING_SAMPLE_RATE` | `1.0` | no — use `0.1` for prod |

---

## 7. How to run

```bash
# 1. Fix Main.java and Flyway dir (see BUGS.md)
# 2. Build
mvn package -DskipTests
# 3. Start
docker compose up -d
curl http://localhost:8091/actuator/health
```

---

## 8. Flyway migration

One migration: `V1__create_kyc_audit.sql` creates the `kyc_audit_entries` table.

After fixing BUG 2 (directory rename), verify:
```bash
docker exec nexus-postgres psql -U nexus -d nexus_kyc \
  -c "SELECT version, description, success FROM flyway_schema_history;"
```

---

## 9. Cost control tips

- `HardRuleValidator` runs BEFORE any AI call — bad documents are rejected without API cost.
- `DocumentQualityValidator` checks blur/glare/partial BEFORE AI — saves ~$0.01/document on rejections.
- In development: use small (low-resolution) test images to minimize gpt-4o vision tokens.
- `resilience4j.retry.kyc-vision-retry`: 3 attempts with 2s backoff — set `max-attempts: 1` in dev to avoid paying 3x for failed tests.
