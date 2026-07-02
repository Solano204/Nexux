# nexus-identity-service — Complete Dependency & Run Guide

## 1. What you need installed

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | **25** | `--enable-preview`, virtual threads |
| Maven | 3.9+ | Build tool |
| Docker Desktop | 24+ with Compose v2 | All infrastructure |
| AWS CLI v2 | any | LocalStack interaction + keystore scripts |
| keytool | bundled with JDK | Generate JWT keystore |

Check:
```bash
java -version          # must say 25
mvn -version           # 3.9+
docker compose version # v2 (not v1 docker-compose)
keytool -help          # bundled with JDK — should always be present
aws --version          # optional, for LocalStack verification
```

---

## 2. ONE-TIME setup before first run

### 2a. Generate the JWT KeyStore

The identity service signs JWTs with an RSA private key stored in a JKS file.
This must exist before the service starts.

```bash
chmod +x scripts/generate-keystore.sh
./scripts/generate-keystore.sh
# Creates: keys/nexus-identity.jks
```

Add `keys/` to your `.gitignore`:
```bash
echo "keys/" >> .gitignore
```

In production, use a strong password and store the file + password in a secrets manager:
```bash
KEYSTORE_PASSWORD=my-very-strong-prod-password ./scripts/generate-keystore.sh
```

### 2b. Build the JAR

```bash
mvn package -DskipTests
```

---

## 3. Runtime dependencies

### 3.1 Always required at startup

| Service | Port | Why the identity service needs it |
|---------|------|-----------------------------------|
| **PostgreSQL 16** | 5432 | Primary data store — users, sessions, KYC, outbox, audit_log |
| **Redis 7** | 6379 | JWT blacklist + session cache + failed login tracking + OTP |
| **nexus-config-service** | 8888 | Loads all configuration (`fail-fast: true` — won't start without it) |
| **nexus-discovery-service** | 8761 | Registers itself as `nexus-identity-service` for API Gateway routing |

### 3.2 Required for full functionality

| Service | Port | What breaks without it |
|---------|------|------------------------|
| **Kafka** | 9092 | SAGA command consumer (`saga.commands`) won't start; Cloud Bus won't work |
| **LocalStack / real AWS S3** | 4566 (local) | KYC document upload fails |
| **LocalStack / real AWS SQS** | 4566 (local) | KYC initiation message not published — KYC pipeline breaks |
| **nexus-zipkin** | 9411 | Service starts fine; distributed traces not visible |
| **nexus-loki** | 3100 | Logs print to console but not in Grafana |
| **OpenAI API** | external | KYC rejection explanation AI falls back to template — service still works |

### 3.3 Downstream consumers of this service's events

These are NOT required for the identity service to start — but they must be running for the full onboarding flow to work:

| Service | Consumes |
|---------|----------|
| nexus-saga-orchestrator | `users.registered`, `identity.verified`, `identity.rejected` (via Debezium → Kafka) |
| nexus-audit-service | All identity events |
| nexus-notification-service | `identity.verified`, `identity.rejected` |

---

## 4. Maven dependencies explained

Your `pom.xml` is complete. No missing dependencies needed for compilation.

However, **one dependency is missing for Loki log shipping** (same as gateway):

```xml
<!-- Add to pom.xml — required by logback-spring.xml LOKI appender -->
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>1.5.2</version>
</dependency>
```

### Key dependencies and why they're here

| Dependency | Purpose |
|-----------|---------|
| `spring-boot-starter-web` | Tomcat + MVC for REST endpoints |
| `spring-boot-starter-data-jpa` + `postgresql` | JPA / Hibernate → PostgreSQL |
| `flyway-core` + `flyway-database-postgresql` | Database migrations (V1-V6 in `db/migration/`) |
| `spring-boot-starter-data-redis` + `lettuce-core` | JWT blacklist, session cache |
| `spring-cloud-starter-config` | Load config from nexus-config-service |
| `spring-cloud-starter-netflix-eureka-client` | Register with Eureka |
| `spring-cloud-bus` + `spring-cloud-stream-binder-kafka` | Config refresh broadcast |
| `spring-kafka` | Consume `saga.commands` Kafka topic |
| `java-jwt` + `jwks-rsa` | Sign RS256 JWTs + publish JWKS endpoint |
| `bcpkix-jdk18on` (Bouncy Castle) | RSA key generation for JwtKeyManager |
| `spring-ai-openai-spring-boot-starter` | KYC rejection explanation via GPT-4o-mini |
| `hypersistence-utils-hibernate-63` | `@Type(JsonType.class)` for JSONB columns in PostgreSQL |
| `software.amazon.awssdk:s3` | Upload KYC documents to S3 |
| `software.amazon.awssdk:sqs` | Publish to SQS `nexus-kyc-documents-pending` |
| `software.amazon.awssdk:url-connection-client` | Sync HTTP client for AWS SDK (compatible with virtual threads) |
| `logstash-logback-encoder` | Structured JSON logs (already in pom.xml) |

---

## 5. Environment variables reference

| Variable | Default | Required? | Description |
|----------|---------|-----------|-------------|
| `SPRING_PROFILES_ACTIVE` | — | **YES** | `docker`, `dev`, or `production` |
| `DATASOURCE_URL` | `jdbc:postgresql://nexus-postgres:5432/nexus_identity` | no | PostgreSQL JDBC URL |
| `POSTGRES_USER` | `nexus` | no | Database user |
| `POSTGRES_PASSWORD` | `""` | prod: **YES** | Database password |
| `REDIS_HOST` | `nexus-redis` | no | Redis hostname |
| `REDIS_PORT` | `6379` | no | Redis port |
| `REDIS_PASSWORD` | `""` | prod: **YES** | Redis password |
| `KAFKA_BOOTSTRAP_SERVERS` | `nexus-kafka:9092` | no | Kafka brokers |
| `EUREKA_DEFAULT_ZONE` | `http://nexus-discovery-service:8761/eureka/` | no | Eureka URL |
| `JWT_KEYSTORE_PATH` | `/app/keys/nexus-identity.jks` | **YES** | Path to JKS file |
| `JWT_KEYSTORE_PASSWORD` | `changeme` | **YES** | JKS password |
| `AWS_REGION` | `us-east-1` | no | AWS region |
| `AWS_ACCESS_KEY_ID` | `""` | prod: **YES** | AWS credentials |
| `AWS_SECRET_ACCESS_KEY` | `""` | prod: **YES** | AWS credentials |
| `AWS_ENDPOINT_OVERRIDE` | `""` | dev only | LocalStack URL (`http://nexus-localstack:4566`) |
| `KYC_DOCUMENTS_BUCKET` | `nexus-kyc-documents` | no | S3 bucket name |
| `KYC_SQS_QUEUE_URL` | `""` | **YES** | Full SQS queue URL |
| `OPENAI_API_KEY` | `""` | no | Empty = KYC rejection uses fallback template |
| `ZIPKIN_ENDPOINT` | `http://nexus-zipkin:9411/api/v2/spans` | no | Zipkin collector |
| `LOKI_URL` | `http://nexus-loki:3100/loki/api/v1/push` | no | Loki push URL |
| `TRACING_SAMPLE_RATE` | `1.0` | no | `0.1` recommended for production |
| `ENVIRONMENT` | `local` | no | Metric tag |

---

## 6. How to run

### 6a. Full Docker Compose (everything starts together)

```bash
# 1. Generate JWT keystore (one-time)
./scripts/generate-keystore.sh

# 2. Build JAR
mvn package -DskipTests

# 3. Start everything
docker compose up -d

# 4. Watch logs
docker compose logs -f nexus-identity-service

# 5. Verify health
curl http://localhost:8083/actuator/health | jq .
```

### 6b. IDE / local Maven (dev profile)

```bash
# Start only infrastructure
docker compose up -d nexus-postgres nexus-redis nexus-kafka \
    nexus-config-service nexus-discovery-service nexus-localstack

# Generate keystore into ./keys/
./scripts/generate-keystore.sh

# Run from IDE with:
#   VM options:  --enable-preview
#   Active profiles: dev

# OR from terminal:
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="--enable-preview" \
  -Dspring-boot.run.profiles=dev \
  -DJWT_KEYSTORE_PATH=./keys/nexus-identity.jks \
  -DJWT_KEYSTORE_PASSWORD=dev_keystore_password \
  -DAWS_ENDPOINT_OVERRIDE=http://localhost:4566
```

### 6c. Run tests

```bash
# Unit tests only (no Docker)
mvn test -Dgroups="unit" --no-transfer-progress

# Integration tests (Docker required for Testcontainers)
mvn test -Dgroups="integration" \
  -DJWT_KEYSTORE_PATH=./keys/nexus-identity.jks \
  -DJWT_KEYSTORE_PASSWORD=dev_keystore_password \
  --no-transfer-progress
```

---

## 7. Database migrations

Flyway runs automatically on startup. Migration files are in `src/main/resources/db/migration/`:

| File | What it creates |
|------|----------------|
| `V1__create_users.sql` | `users` table — main user entity |
| `V2__create_sessions.sql` | `sessions` table — active JWT sessions |
| `V3__create_kyc_verifications.sql` | `kyc_verifications` — KYC attempt records |
| `V4__create_audit_log.sql` | `audit_log` — immutable append-only audit trail |
| `V5__create_outbox.sql` | `outbox` — Debezium CDC outbox for events |
| `V6__create_password_history.sql` | `password_history` — prevents password reuse |

To check migration status manually:
```bash
docker exec nexus-postgres psql -U nexus -d nexus_identity \
  -c "SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank;"
```

---

## 8. Observability URLs

| Dashboard | URL |
|-----------|-----|
| Identity Service health | http://localhost:8083/actuator/health |
| Identity Service metrics | http://localhost:8083/actuator/prometheus |
| JWKS endpoint (gateway uses this) | http://localhost:8083/api/v1/auth/.well-known/jwks.json |
| Grafana | http://localhost:3000 (admin/admin) |
| Prometheus | http://localhost:9090 |
| Zipkin | http://localhost:9411 |
| Eureka | http://localhost:8761 |
| LocalStack S3 | http://localhost:4566/nexus-kyc-documents |

---

## 9. Common startup problems

### "Failed to connect to nexus-postgres:5432"
PostgreSQL container isn't healthy yet.
```bash
docker compose ps nexus-postgres
# Wait for "(healthy)" then restart identity service:
docker compose restart nexus-identity-service
```

### "Keystore file not found: /app/keys/nexus-identity.jks"
The keys directory volume is empty.
```bash
./scripts/generate-keystore.sh
# Then restart:
docker compose restart nexus-identity-service
```

### Flyway error: "Found non-empty schema(s) 'public' without schema history table"
The database has data from a previous run but no Flyway history.
The `baseline-on-migrate: true` in dev handles this automatically.
If it persists: `docker compose down -v` (removes volumes) then `docker compose up -d`.

### "Connection refused: nexus-localstack:4566"
LocalStack is still starting. Check: `docker compose ps nexus-localstack`.
The service will retry automatically — LocalStack takes 20-30s to fully initialize.

### Spring AI / OpenAI errors at startup
Leave `OPENAI_API_KEY` empty — the KYC rejection explainer has a fallback template and won't crash without a valid key.
