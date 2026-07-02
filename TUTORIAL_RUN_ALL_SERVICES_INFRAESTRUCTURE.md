# NEXUS Platform — Complete Run Tutorial
**Last updated:** 2026-06-14  
**Author:** Claude Sonnet 4.6 (config audit & tutorial)  
**Scope:** Configuration-only analysis — how to run every service in every mode

---

## ARE YOU READY TO RUN? — Status Check

| Check | Status | Notes |
|---|---|---|
| Kafka listeners fixed | ✅ | Internal `nexus-kafka:9092`, external `localhost:19092` |
| Discovery service dev config | ✅ | No longer hijacked by fraud-service config |
| Database names normalized | ✅ | Plural form: `nexus_accounts`, `nexus_transactions` |
| Platform config files exist | ✅ | `nexus-audit-query-jvm`, `nexus-fraud-service-dev` fixed |
| Logback → Loki (all services) | ✅ | Profile-gated, async, neverBlock |
| CI workflows (7 services) | ✅ | Created |
| `GIT_TOKEN` env var | ⚠️ REQUIRED | Config service has NO default — must be set before docker/prod run |
| JWT Keystore file | ⚠️ REQUIRED | Identity service needs `keys/nexus-identity.jks` — generate first |
| PostgreSQL databases created | ⚠️ REQUIRED | 10 databases needed — see Database Bootstrap section |
| `OPENAI_API_KEY` | ⚠️ OPTIONAL | AI services start without it but AI features return errors |
| Kafka port in dev profiles | ⚠️ NOTE | Dev profiles use `localhost:9092`. Shared infra (`kafka/`) uses `localhost:19092` — use per-service compose for dev infra |
| Per-service docker-compose DB name | ⚠️ NOTE | `nexus-account-service/docker-compose.yml` still sets `POSTGRES_DB: nexus_account` (singular) — but the app connects to `nexus_accounts`. Fix the env var before using it |

**Bottom line:** You are ready to run, but complete the steps in the Prerequisites section first.

---

## Table of Contents

1. [Platform Architecture](#1-platform-architecture)
2. [Prerequisites & Tools](#2-prerequisites--tools)
3. [Environment Variables](#3-environment-variables)
4. [Before First Run — One-Time Setup](#4-before-first-run--one-time-setup)
5. [Running Mode: DEV (local IDE)](#5-running-mode-dev-local-ide)
6. [Running Mode: DOCKER (per-service compose)](#6-running-mode-docker-per-service-compose)
7. [Running Mode: FULL PLATFORM (all services)](#7-running-mode-full-platform-all-services)
8. [Running Mode: PRODUCTION](#8-running-mode-production)
9. [Service-by-Service Reference](#9-service-by-service-reference)
10. [Database Bootstrap](#10-database-bootstrap)
11. [Port Map](#11-port-map)
12. [Health Check URLs](#12-health-check-urls)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Platform Architecture

```
                         ┌─────────────────────────────────┐
                         │      nexus-api-gateway :8080     │
                         │  (Spring Cloud Gateway + Netty)  │
                         └──────────────┬──────────────────┘
                                        │ routes by path
           ┌─────────────┬──────────────┼───────────────┬──────────────┐
           ▼             ▼              ▼               ▼              ▼
    identity:8083  account:8085  transaction:8086  ledger:8088  analytics:8092
    fraud:8087     ai-assistant:8090  ai-kyc:8091  risk:8094   notification:8089
                                      saga:8095
                              audit-query-jvm:8097
                              audit-write-native:8096 (Quarkus)

  ┌────────────────────────────────────────────────────────────────────────┐
  │                        SPRING CLOUD INFRA                              │
  │  nexus-config-service:8888   nexus-discovery-service:8761              │
  └────────────────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────────────────┐
  │                          DATA LAYER                                    │
  │  PostgreSQL+pgvector:5432  MongoDB:27017  Redis:6379                   │
  │  Kafka(KRaft):9092(int)/19092(ext)  Elasticsearch:9200                 │
  └────────────────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────────────────┐
  │                         OBSERVABILITY                                  │
  │  Zipkin:9411  Prometheus:9090  Loki:3100  Grafana:3000  Kafka UI:8190  │
  └────────────────────────────────────────────────────────────────────────┘
```

### Startup Order (mandatory)

```
kafka/docker-compose.yml (infra)
       ↓
nexus-config-service        ← needs GIT_TOKEN
       ↓
nexus-discovery-service
       ↓
nexus-identity-service      ← needs JWT keystore
       ↓
nexus-account-service  ─┐
nexus-transaction-service─┤
nexus-fraud-service   ────┤  All can start in parallel after identity+discovery
nexus-ledger-service  ────┤
nexus-notification-service┤
nexus-analytics-service   ┤
nexus-risk-scoring-service┤
nexus-ai-assistant-service┤
nexus-ai-kyc-service  ────┤
nexus-audit-query-jvm ────┤
nexus-saga-orchestrator   ┘
       ↓
nexus-api-gateway           ← starts last (needs all services in Eureka)
```

---

## 2. Prerequisites & Tools

### Required

| Tool | Minimum Version | Check command |
|---|---|---|
| Java (Temurin) | 21+ (services use virtual threads) | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Docker Desktop | 24+ | `docker --version` |
| Docker Compose | v2 (plugin, not standalone) | `docker compose version` |
| Git | any | `git --version` |

### Optional (but recommended)

| Tool | Purpose |
|---|---|
| `keytool` (bundled with JDK) | Generate JWT keystore |
| `psql` | Inspect/debug PostgreSQL |
| `redis-cli` | Inspect Redis |
| `curl` or Postman | Health checks + API testing |

---

## 3. Environment Variables

Create a `.env` file in the **root** of the project (`NEXUS/`). This file is shared by all modes.

```bash
# ── REQUIRED ──────────────────────────────────────────────────────
# GitHub token used by nexus-config-service to clone nexus-platform-config
# The repo is: https://github.com/Solano204/nexus-platform-config.git
GIT_TOKEN=ghp_your_github_personal_access_token_here

# Git username (has a default of Solano204, but set it explicitly)
GIT_USERNAME=Solano204

# Config server credentials (used by ALL services to authenticate)
CONFIG_SERVER_USERNAME=nexus-config
CONFIG_SERVER_PASSWORD=nexus-config-password

# ── DATABASES ─────────────────────────────────────────────────────
POSTGRES_USER=nexus
POSTGRES_PASSWORD=nexus_dev_password

# ── OPTIONAL — AI FEATURES ────────────────────────────────────────
# Services start without this; AI endpoints will return errors
OPENAI_API_KEY=sk-...your-key...

# ── OPTIONAL — JWT KEYSTORE (if not using defaults) ───────────────
JWT_KEYSTORE_PASSWORD=dev_keystore_password

# ── OPTIONAL — AWS (identity KYC doc storage) ─────────────────────
# In dev: LocalStack handles S3+SQS automatically (no real AWS needed)
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test

# ── OPTIONAL — REDIS PASSWORD ─────────────────────────────────────
# Leave empty for dev (no password on dev Redis)
REDIS_PASSWORD=

# ── OPTIONAL — TRACING ────────────────────────────────────────────
TRACING_SAMPLE_RATE=1.0
ENVIRONMENT=local-docker
```

---

## 4. Before First Run — One-Time Setup

### 4.1 Generate the JWT Keystore (Identity Service)

The identity service WILL NOT START without this file.

```bash
# From the nexus-identity-service directory:
cd nexus-identity-service
mkdir -p keys
keytool -genkeypair \
  -alias nexus-identity \
  -keyalg RSA \
  -keysize 2048 \
  -keystore keys/nexus-identity.jks \
  -storepass dev_keystore_password \
  -keypass dev_keystore_password \
  -dname "CN=nexus-identity, OU=Nexus, O=Nexus Platform, L=San Jose, ST=CA, C=US" \
  -validity 3650
```

If the service has a `scripts/generate-keystore.sh`:
```bash
cd nexus-identity-service
bash scripts/generate-keystore.sh
```

### 4.2 Set Your GitHub Token

The `nexus-config-service` clones `https://github.com/Solano204/nexus-platform-config.git` at startup. You need a GitHub Personal Access Token with `repo` read scope.

```bash
export GIT_TOKEN=ghp_your_token_here
# Or add to your shell profile:
echo 'export GIT_TOKEN=ghp_...' >> ~/.bashrc
```

### 4.3 Bootstrap PostgreSQL Databases

All services need their database to exist before Flyway runs. If using a single shared PostgreSQL, create all databases at once.

See **Section 10 — Database Bootstrap** for the full SQL.

### 4.4 Build All Services

Run once to compile everything and create Docker images:

```bash
# From NEXUS root:
mvn clean package -DskipTests

# Or build Docker images for each service:
cd nexus-config-service   && docker build -t nexusplatform/nexus-config-service:latest . && cd ..
cd nexus-discovery-service && docker build -t nexusplatform/nexus-discovery-service:latest . && cd ..
# ... (repeat for each service, or use the CI workflows)
```

---

## 5. Running Mode: DEV (local IDE)

**Use this when:** You want to run ONE service from your IDE while keeping infra in Docker.

**How it works:**
- Profile `dev` is active: `SPRING_PROFILES_ACTIVE=dev`
- Spring Cloud Config is DISABLED — service reads its own `application.yml` + `application-dev.yml`
- Spring Cloud Bus is DISABLED — no Kafka needed for config refresh
- All infrastructure (Postgres, Redis, Kafka, Elasticsearch) uses `localhost` addresses

### Step 1 — Start the shared infrastructure

Use the **per-service** docker-compose (not `kafka/`), because it exposes Kafka on `localhost:9092` which matches the dev profile.

```bash
# Example: Running account-service in dev mode
cd nexus-account-service
docker compose up -d nexus-postgres nexus-mongodb nexus-redis nexus-kafka nexus-zookeeper
```

**Why not `kafka/docker-compose.yml`?** The shared platform Kafka exposes port `19092` on the host, but all dev profiles use `localhost:9092`. The per-service docker-compose files include their own Kafka at port `9092:9092`.

### Step 2 — Start Config + Discovery (optional in dev)

In dev profile, Config and Discovery are NOT required (Config is disabled, Discovery can be skipped). But if you want Eureka registration:

```bash
# Start discovery separately on localhost:
cd nexus-discovery-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# It will start on port 8761 with standalone mode (no peer, no config server)
```

### Step 3 — Run the target service

**From IntelliJ IDEA:**
1. Open the service module
2. Edit Run Configuration → Add VM option: `-Dspring.profiles.active=dev`
3. Or set environment variable: `SPRING_PROFILES_ACTIVE=dev`
4. Run the main class

**From Maven:**
```bash
cd nexus-account-service
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

**From terminal with explicit profile:**
```bash
java -jar target/nexus-account-service-*.jar --spring.profiles.active=dev
```

### DEV Profile — Service-Specific Notes

#### nexus-config-service (port 8888)
```bash
# Config service does NOT use a dev profile — it IS the config source.
# Just run it directly. Requires GIT_TOKEN.
export GIT_TOKEN=ghp_...
mvn spring-boot:run
```

#### nexus-discovery-service (port 8761)
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# Standalone Eureka: no peers, no config server, self-preservation OFF
```

#### nexus-identity-service (port 8083)
```bash
# Needs keystore file at keys/nexus-identity.jks (see Section 4.1)
export SPRING_PROFILES_ACTIVE=dev
export JWT_KEYSTORE_PATH=./keys/nexus-identity.jks
export JWT_KEYSTORE_PASSWORD=dev_keystore_password
# AWS calls go to LocalStack (start it first from docker-compose):
# docker compose up -d nexus-localstack
mvn spring-boot:run
```

#### nexus-account-service (port 8085)
```bash
# DB: nexus_accounts at localhost:5432
# Kafka: localhost:9092
export SPRING_PROFILES_ACTIVE=dev
export OPENAI_API_KEY=sk-...   # optional
mvn spring-boot:run
```

#### nexus-transaction-service (port 8086)
```bash
# DB: nexus_transactions at localhost:5432
# Elasticsearch: localhost:9200
# Kafka: localhost:9092 (Kafka Streams enabled)
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

#### nexus-fraud-service (port 8087)
```bash
# DB: nexus_fraud at localhost:5432
# Redis: localhost:6379
# Elasticsearch: localhost:9200
# Kafka: localhost:9092
export SPRING_PROFILES_ACTIVE=dev
export OPENAI_API_KEY=sk-...   # optional
mvn spring-boot:run
```

#### nexus-ledger-service (port 8088)
```bash
# DB: nexus_ledger at localhost:5432
# MongoDB: localhost:27017
# Kafka: localhost:9092
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

#### nexus-notification-service (port 8089)
```bash
# MongoDB: localhost:27017
# Redis: localhost:6379
# Kafka: localhost:9092
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

#### nexus-ai-assistant-service (port 8090)
```bash
# DB: nexus_ai_assistant at localhost:5432 (pgvector)
# Redis: localhost:6379
# Kafka: localhost:9092
# Ollama: localhost:11434 (start separately if using local LLM)
export SPRING_PROFILES_ACTIVE=dev
export OPENAI_API_KEY=sk-...
mvn spring-boot:run
```

#### nexus-ai-kyc-service (port 8091)
```bash
# DB: nexus_kyc at localhost:5432
# MongoDB: localhost:27017
# Kafka: localhost:9092
# REQUIRES GPT-4o Vision (not optional for KYC to work)
export SPRING_PROFILES_ACTIVE=dev
export OPENAI_API_KEY=sk-...
mvn spring-boot:run
```

#### nexus-analytics-service (port 8092)
```bash
# Elasticsearch: localhost:9200
# Redis: localhost:6379
# Kafka Streams: localhost:9092
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

#### nexus-risk-scoring-service (port 8094)
```bash
# DB: nexus_risk at localhost:5432
# Redis: localhost:6379
# Kafka: localhost:9092
# Uses gpt-4o (not mini) — temperature=0.0 deterministic
export SPRING_PROFILES_ACTIVE=dev
export OPENAI_API_KEY=sk-...
mvn spring-boot:run
```

#### nexus-saga-orchestrator (port 8095)
```bash
# DB: nexus_saga at localhost:5432
# Kafka: localhost:9092
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

#### nexus-audit-query-jvm (port 8097)
```bash
# DB: nexus_audit at localhost:5432 (pgvector)
# Elasticsearch: localhost:9200
# MongoDB: localhost:27017
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

#### nexus-api-gateway (port 8080)
```bash
# Redis: localhost:6379
# Eureka: localhost:8761
# Routes all point to localhost:<port> in dev profile
# JWT JWKS from: http://localhost:8083/api/v1/auth/.well-known/jwks.json
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

---

## 6. Running Mode: DOCKER (per-service compose)

**Use this when:** You want to test a single service fully containerized with all its dependencies.

Each service folder has its own `docker-compose.yml` that starts everything needed for that service.

### Step 1 — Build the service Docker image

```bash
cd nexus-account-service
mvn clean package -DskipTests
docker build -t nexusplatform/nexus-account-service:latest .
```

### Step 2 — Start with compose

```bash
cd nexus-account-service
docker compose up -d

# Watch logs:
docker compose logs -f nexus-account-service

# Check health:
curl http://localhost:8085/actuator/health
```

### Step 3 — Stop when done

```bash
docker compose down           # stops containers, keeps volumes
docker compose down -v        # also removes volumes (fresh start)
```

### Per-service Docker Compose — Quick Reference

| Service | Command | Health URL |
|---|---|---|
| nexus-config-service | `cd nexus-config-service && docker compose up -d` | `localhost:8888/actuator/health` |
| nexus-discovery-service | `cd nexus-discovery-service && docker compose up -d` | `localhost:8761/actuator/health` |
| nexus-identity-service | `cd nexus-identity-service && docker compose up -d` | `localhost:8083/actuator/health` |
| nexus-account-service | `cd nexus-account-service && docker compose up -d` | `localhost:8085/actuator/health` |
| nexus-transaction-service | `cd nexus-transaction-service && docker compose up -d` | `localhost:8086/actuator/health` |
| nexus-fraud-service | `cd nexus-fraud-service && docker compose up -d` | `localhost:8087/actuator/health` |
| nexus-ledger-service | `cd nexus-ledger-service && docker compose up -d` | `localhost:8088/actuator/health` |
| nexus-notification-service | `cd nexus-notification-service && docker compose up -d` | `localhost:8089/actuator/health` |
| nexus-ai-assistant-service | `cd nexus-ai-assistant-service && docker compose up -d` | `localhost:8090/actuator/health` |
| nexus-ai-kyc-service | `cd nexus-ai-kyc-service && docker compose up -d` | `localhost:8091/actuator/health` |
| nexus-analytics-service | `cd nexus-analytics-service && docker compose up -d` | `localhost:8092/actuator/health` |
| nexus-risk-scoring-service | `cd nexus-risk-scoring-service && docker compose up -d` | `localhost:8094/actuator/health` |
| nexus-saga-orchestrator | `cd nexus-saga-orchestrator && docker compose up -d` | `localhost:8095/actuator/health` |
| nexus-audit-query-jvm | `cd nexus-audit-query-jvm && docker compose up -d` | `localhost:8097/actuator/health` |
| nexus-api-gateway | `cd nexus-api-gateway && docker compose up -d` | `localhost:8080/actuator/health` |

### ⚠️ Known issue — account-service docker-compose DB name

`nexus-account-service/docker-compose.yml` sets `POSTGRES_DB: nexus_account` (singular) as the default database, but the application connects to `nexus_accounts` (plural). Before running:

```bash
# In nexus-account-service/docker-compose.yml, find:
#   DATASOURCE_URL: jdbc:postgresql://nexus-postgres:5432/nexus_account
# Change to:
#   DATASOURCE_URL: jdbc:postgresql://nexus-postgres:5432/nexus_accounts
```

---

## 7. Running Mode: FULL PLATFORM (all services)

**Use this when:** You want to run the entire NEXUS platform locally.

### Step 1 — Start shared infrastructure

```bash
cd kafka
docker compose up -d
```

This starts: **Redis, Kafka (KRaft), Kafka UI, Elasticsearch** and creates all 30 Kafka topics.

Wait for Kafka to be healthy:
```bash
docker compose ps
# nexus-kafka should show "healthy"
```

Access:
- Kafka UI: http://localhost:8190
- Elasticsearch: http://localhost:9200
- Redis: `redis-cli -h localhost -p 6379 ping`

### Step 2 — Build all Docker images

```bash
# From NEXUS root, build each service:
for service in nexus-config-service nexus-discovery-service nexus-identity-service \
  nexus-account-service nexus-transaction-service nexus-fraud-service \
  nexus-ledger-service nexus-notification-service nexus-ai-assistant-service \
  nexus-ai-kyc-service nexus-analytics-service nexus-risk-scoring-service \
  nexus-saga-orchestrator nexus-audit-query-jvm nexus-api-gateway; do
  echo "=== Building $service ==="
  cd $service
  mvn clean package -DskipTests -q
  docker build -t nexusplatform/$service:latest . -q
  cd ..
done
```

### Step 3 — Start nexus-config-service

```bash
cd nexus-config-service
export GIT_TOKEN=ghp_your_token
export CONFIG_SERVER_USERNAME=nexus-config
export CONFIG_SERVER_PASSWORD=nexus-config-password
docker compose up -d
```

Wait until healthy:
```bash
docker compose logs -f nexus-config-service
# Look for: "Started NexusConfigServiceApplication in X seconds"
curl http://localhost:8888/actuator/health
```

Verify it can serve config:
```bash
curl -u nexus-config:nexus-config-password \
  http://localhost:8888/nexus-account-service/docker
```

### Step 4 — Start nexus-discovery-service

```bash
cd ../nexus-discovery-service
docker compose up -d

# Wait for it:
curl http://localhost:8761/actuator/health
# Check dashboard: http://localhost:8761
```

### Step 5 — Start nexus-identity-service

```bash
cd ../nexus-identity-service

# Keystore MUST exist:
ls keys/nexus-identity.jks   # if missing, run Step 4.1

docker compose up -d

# Watch startup (takes ~60s first time):
docker compose logs -f nexus-identity-service
curl http://localhost:8083/actuator/health
```

### Step 6 — Start all remaining services (parallel)

Open separate terminals or use:

```bash
# Terminal 1: Financial services
cd nexus-account-service    && docker compose up -d && cd ..
cd nexus-transaction-service && docker compose up -d && cd ..
cd nexus-ledger-service     && docker compose up -d && cd ..
cd nexus-saga-orchestrator  && docker compose up -d && cd ..

# Terminal 2: AI services
cd nexus-fraud-service      && docker compose up -d && cd ..
cd nexus-risk-scoring-service && docker compose up -d && cd ..
cd nexus-ai-assistant-service && docker compose up -d && cd ..
cd nexus-ai-kyc-service     && docker compose up -d && cd ..

# Terminal 3: Support services
cd nexus-notification-service && docker compose up -d && cd ..
cd nexus-analytics-service   && docker compose up -d && cd ..
cd nexus-audit-query-jvm     && docker compose up -d && cd ..
```

### Step 7 — Start nexus-api-gateway (LAST)

```bash
cd nexus-api-gateway
docker compose up -d

curl http://localhost:8080/actuator/health
```

### Step 8 — Verify all services registered in Eureka

Open http://localhost:8761 — you should see all 13 client services registered:
- nexus-account-service
- nexus-transaction-service
- nexus-identity-service
- nexus-fraud-service
- nexus-ledger-service
- nexus-notification-service
- nexus-ai-assistant-service
- nexus-ai-kyc-service
- nexus-analytics-service
- nexus-risk-scoring-service
- nexus-saga-orchestrator
- nexus-audit-query-jvm
- nexus-api-gateway

### Step 9 — Test the gateway

```bash
# Health check:
curl http://localhost:8080/actuator/health

# Register a user (public endpoint):
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@nexus.com","password":"Test1234!"}'

# Get JWKS (confirms identity service is reachable):
curl http://localhost:8080/api/v1/auth/.well-known/jwks.json
```

---

## 8. Running Mode: PRODUCTION

**Use this when:** Deploying to a real server or cloud environment.

### Key differences from docker mode

| Setting | Docker (dev) | Production |
|---|---|---|
| Kafka | Internal KRaft, no auth | External managed Kafka (MSK, Confluent) |
| PostgreSQL | Local container | Managed RDS / Cloud SQL |
| Config source | Local file-based git | GitHub repo with real token |
| Redis | No password | Password protected |
| Elasticsearch | No auth | Auth enabled |
| Tracing sample rate | 1.0 (100%) | 0.1 (10%) |
| JVM heap | Default | Tuned with `-Xms` / `-Xmx` |
| Flyway | `baseline-on-migrate: true` | `validate-on-migrate: true`, no baseline |
| pgvector schema init | `initialize-schema: true` | `initialize-schema: false` |

### Production profile activation

All services use `application-prod.yml` (or are configured via the platform config repo in the `production` label).

```bash
SPRING_PROFILES_ACTIVE=production
```

### Required production environment variables (per service)

```bash
# All services need:
GIT_TOKEN=<github-pat>
CONFIG_SERVER_USERNAME=<secret>
CONFIG_SERVER_PASSWORD=<secret>
DATASOURCE_URL=jdbc:postgresql://<prod-db-host>:5432/<db-name>
POSTGRES_USER=<prod-user>
POSTGRES_PASSWORD=<prod-password>
KAFKA_BOOTSTRAP_SERVERS=<prod-kafka-host>:9092
REDIS_HOST=<prod-redis-host>
REDIS_PASSWORD=<prod-redis-password>
EUREKA_DEFAULT_ZONE=http://nexus-discovery-service:8761/eureka/
ZIPKIN_ENDPOINT=http://nexus-zipkin:9411/api/v2/spans
OPENAI_API_KEY=<prod-key>
ENVIRONMENT=production
TRACING_SAMPLE_RATE=0.1

# Identity service only:
JWT_KEYSTORE_PATH=/app/keys/nexus-identity.jks
JWT_KEYSTORE_PASSWORD=<prod-keystore-pass>
KYC_DOCUMENTS_BUCKET=<prod-s3-bucket>
KYC_SQS_QUEUE_URL=<prod-sqs-url>
AWS_REGION=us-east-1
```

### Production startup command (per container)

```bash
java \
  -Xms512m -Xmx1g \
  -XX:+UseG1GC \
  -Dspring.profiles.active=production \
  -jar /app/nexus-account-service.jar
```

---

## 9. Service-by-Service Reference

### nexus-config-service

| Property | Value |
|---|---|
| Port | 8888 |
| Profile (docker/prod) | `docker` or `production` |
| Config source | GitHub: `Solano204/nexus-platform-config` |
| Auth | Basic: `nexus-config` / `nexus-config-password` |
| Dependencies | Kafka (Cloud Bus) |
| Critical env var | `GIT_TOKEN` (no default — service FAILS without it) |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
export GIT_TOKEN=ghp_...
mvn spring-boot:run
```

**Docker run:**
```bash
cd nexus-config-service
docker compose up -d
```

---

### nexus-discovery-service

| Property | Value |
|---|---|
| Port | 8761 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Dashboard | http://localhost:8761 |
| Auth | `eureka-admin` / `eureka-admin-password` |
| Dependencies (dev) | None (standalone) |
| Dependencies (docker) | nexus-config-service |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-discovery-service
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-discovery-service
docker compose up -d
```

---

### nexus-identity-service

| Property | Value |
|---|---|
| Port | 8083 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Database | PostgreSQL: `nexus_identity` |
| Dependencies | PostgreSQL, Redis, Kafka, LocalStack (dev), Config, Discovery |
| Special | Needs `keys/nexus-identity.jks` keystore file |
| JWT JWKS | `GET /api/v1/auth/.well-known/jwks.json` |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-identity-service
export JWT_KEYSTORE_PATH=./keys/nexus-identity.jks
export JWT_KEYSTORE_PASSWORD=dev_keystore_password
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-identity-service
# Must have keys/ directory with the keystore:
ls keys/nexus-identity.jks
docker compose up -d
```

---

### nexus-account-service

| Property | Value |
|---|---|
| Port | 8085 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Database | PostgreSQL: `nexus_accounts` (pgvector required) |
| MongoDB | `nexus_accounts` (CQRS read side) |
| Dependencies | PostgreSQL+pgvector, MongoDB, Redis, Kafka, Config, Discovery |
| AI | pgvector table: `transaction_embeddings` |
| Kafka topics | Consumes: `saga.commands` / Produces: `saga.replies`, `accounts.created` |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-account-service
# Infrastructure (start this docker-compose first):
docker compose up -d nexus-postgres nexus-mongodb nexus-redis nexus-kafka nexus-zookeeper
# Then run the service:
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-account-service
# Fix the DB name in docker-compose.yml first (see Section 6 known issue)
docker compose up -d
```

---

### nexus-transaction-service

| Property | Value |
|---|---|
| Port | 8086 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Database | PostgreSQL: `nexus_transactions` |
| Elasticsearch | `http://localhost:9200` (dev) |
| Dependencies | PostgreSQL, Elasticsearch, Kafka, Config, Discovery |
| Special | Kafka Streams enabled (`nexus-transaction-streams`) |
| State store | `/tmp/kafka-streams-state` |
| Kafka topics | Produces: `transactions.initiated`, `transactions.completed`, `transactions.failed` |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-transaction-service
docker compose up -d nexus-postgres nexus-elasticsearch nexus-kafka nexus-zookeeper
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-transaction-service
docker compose up -d
```

---

### nexus-fraud-service

| Property | Value |
|---|---|
| Port | 8087 |
| Profile (dev) | `dev` (no explicit dev config — uses base `application.yml`) |
| Profile (docker) | `docker` |
| Database | PostgreSQL: `nexus_fraud` (pgvector: `fraud_policy_embeddings`) |
| Dependencies | PostgreSQL+pgvector, Redis, Elasticsearch, Kafka, Config, Discovery |
| AI | OpenAI gpt-4o-mini, temperature 0.1 (conservative — fraud analysis) |
| Kafka topics | Consumes: `saga.commands` / Produces: `fraud.result`, `fraud.flagged` |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-fraud-service
docker compose up -d nexus-postgres nexus-redis nexus-elasticsearch nexus-kafka nexus-zookeeper
export OPENAI_API_KEY=sk-...
mvn spring-boot:run
# Note: no dev profile — base config uses docker hostnames as defaults.
# Override individually:
# mvn spring-boot:run -Dspring.datasource.url=jdbc:postgresql://localhost:5432/nexus_fraud
```

**Docker run:**
```bash
cd nexus-fraud-service
docker compose up -d
```

---

### nexus-ledger-service

| Property | Value |
|---|---|
| Port | 8088 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Database | PostgreSQL: `nexus_ledger` (pgvector: `financial_literacy_embeddings`) |
| MongoDB | `nexus_ledger` (CQRS read side) |
| Dependencies | PostgreSQL+pgvector, MongoDB, Kafka, Config, Discovery |
| Special | SERIALIZABLE isolation — pool size 60 (holds locks longer) |
| Kafka topics | Produces: `ledger.posted`, `ledger.reversed` |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-ledger-service
docker compose up -d nexus-postgres nexus-mongodb nexus-kafka nexus-zookeeper
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-ledger-service
docker compose up -d
```

---

### nexus-notification-service

| Property | Value |
|---|---|
| Port | 8089 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| MongoDB | `nexus_notification` |
| Redis | Rate-limiting + deduplication |
| Dependencies | MongoDB, Redis, Kafka, Config, Discovery |
| AI | OpenAI gpt-4o-mini, temperature 0.7 |
| Kafka topics | Consumes: notification events from all services |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-notification-service
docker compose up -d nexus-mongodb nexus-redis nexus-kafka nexus-zookeeper
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-notification-service
docker compose up -d
```

---

### nexus-ai-assistant-service

| Property | Value |
|---|---|
| Port | 8090 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Database | PostgreSQL: `nexus_ai_assistant` (pgvector: embeddings table) |
| Redis | Session caching |
| Ollama | `http://localhost:11434` — local LLM fallback (mistral:7b) |
| Dependencies | PostgreSQL+pgvector, Redis, Kafka, Config, Discovery, [Ollama optional] |
| AI | OpenAI gpt-4o-mini (cloud) + Ollama mistral:7b (local fallback) |
| Calls services | account:8085, transaction:8086, fraud:8087 |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-ai-assistant-service
docker compose up -d nexus-postgres nexus-redis nexus-kafka nexus-zookeeper
# Optional Ollama (for local LLM):
# docker run -d -p 11434:11434 ollama/ollama
# docker exec -it ollama ollama pull mistral:7b
export OPENAI_API_KEY=sk-...
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-ai-assistant-service
docker compose up -d
```

---

### nexus-ai-kyc-service

| Property | Value |
|---|---|
| Port | 8091 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Database | PostgreSQL: `nexus_kyc` |
| MongoDB | `nexus_kyc` (KYC documents + GridFS) |
| Dependencies | PostgreSQL, MongoDB, Kafka, Config, Discovery |
| AI | GPT-4o Vision (stage 1 extraction) + gpt-4o-mini (stage 2 comparison) |
| Max file upload | 10MB documents |
| S3 bucket | `nexus-kyc-documents` (LocalStack in dev) |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-ai-kyc-service
docker compose up -d nexus-postgres nexus-mongodb nexus-kafka nexus-zookeeper
# Identity service's LocalStack is needed for S3 uploads:
# Start identity service's docker compose first, or set AWS_ENDPOINT_OVERRIDE
export OPENAI_API_KEY=sk-...   # REQUIRED — GPT-4o needed for document extraction
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-ai-kyc-service
docker compose up -d
```

---

### nexus-analytics-service

| Property | Value |
|---|---|
| Port | 8092 |
| Profile (dev) | `dev` (no explicit dev config — override manually) |
| Profile (docker/prod) | `docker` |
| Elasticsearch | Transaction data store + analytics |
| Redis | Caching |
| Dependencies | Elasticsearch, Redis, Kafka, Config, Discovery |
| Special | Kafka Streams (`nexus-analytics-streams`), 4 stream threads |
| AI | OpenAI gpt-4o-mini for insight generation |
| Kafka topics | Consumes transaction events, produces `analytics.anomalies.detected` |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-analytics-service
docker compose up -d nexus-elasticsearch nexus-redis nexus-kafka nexus-zookeeper
mvn spring-boot:run \
  -Dspring.data.elasticsearch.uris=http://localhost:9200 \
  -Dspring.data.redis.host=localhost \
  -Dspring.kafka.bootstrap-servers=localhost:9092
```

**Docker run:**
```bash
cd nexus-analytics-service
docker compose up -d
```

---

### nexus-risk-scoring-service

| Property | Value |
|---|---|
| Port | 8094 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Database | PostgreSQL: `nexus_risk` |
| Redis | Risk profile cache |
| Dependencies | PostgreSQL, Redis, Kafka, Config, Discovery |
| AI | GPT-4o (not mini) — temperature 0.0, deterministic for compliance |
| Rate limiter | 55 requests/minute to OpenAI |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-risk-scoring-service
docker compose up -d nexus-postgres nexus-redis nexus-kafka nexus-zookeeper
export OPENAI_API_KEY=sk-...
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-risk-scoring-service
docker compose up -d
```

---

### nexus-saga-orchestrator

| Property | Value |
|---|---|
| Port | 8095 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Database | PostgreSQL: `nexus_saga` |
| Dependencies | PostgreSQL, Kafka, Config, Discovery |
| Kafka topics | Produces: `saga.commands` / Consumes: `saga.replies`, `saga.completed`, `saga.failed` |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-saga-orchestrator
docker compose up -d nexus-postgres nexus-kafka nexus-zookeeper
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-saga-orchestrator
docker compose up -d
```

---

### nexus-audit-query-jvm

| Property | Value |
|---|---|
| Port | 8097 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Database | PostgreSQL: `nexus_audit` (pgvector: `audit_embeddings`) |
| Elasticsearch | Audit event search |
| MongoDB | Audit documents |
| Dependencies | PostgreSQL+pgvector, Elasticsearch, MongoDB, Config, Discovery |
| AI | OpenAI gpt-4o-mini, temperature 0.1 |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-audit-query-jvm
docker compose up -d nexus-postgres nexus-elasticsearch nexus-mongodb
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-audit-query-jvm
docker compose up -d
```

---

### nexus-api-gateway

| Property | Value |
|---|---|
| Port | 8080 |
| Profile (dev) | `dev` |
| Profile (docker/prod) | `docker` |
| Engine | Netty (reactive, non-blocking) |
| Redis | Rate limiting + JWT blacklist |
| Kafka | Spring Cloud Bus |
| Dependencies | Redis, Kafka, Eureka, identity-service (JWKS) |
| Routes | 10 routes: auth, accounts, transactions, AI, fraud, notifications, ledger, analytics, actuator, webhooks |
| CORS | Allows `localhost:3000`, `localhost:5173` |
| Health | `GET /actuator/health` |

**Dev run:**
```bash
cd nexus-api-gateway
# Needs Redis and Eureka running
docker compose up -d nexus-redis
# Start discovery separately (or use per-service compose)
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**Docker run:**
```bash
cd nexus-api-gateway
docker compose up -d
```

---

## 10. Database Bootstrap

### PostgreSQL — All databases needed

Connect to your PostgreSQL instance and run:

```sql
-- Create all NEXUS databases
CREATE DATABASE nexus_identity;
CREATE DATABASE nexus_accounts;
CREATE DATABASE nexus_transactions;
CREATE DATABASE nexus_fraud;
CREATE DATABASE nexus_ledger;
CREATE DATABASE nexus_kyc;
CREATE DATABASE nexus_ai_assistant;
CREATE DATABASE nexus_risk;
CREATE DATABASE nexus_saga;
CREATE DATABASE nexus_audit;

-- Create the nexus user (if it doesn't exist)
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexus') THEN
    CREATE ROLE nexus LOGIN PASSWORD 'nexus_dev_password';
  END IF;
END
$$;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE nexus_identity TO nexus;
GRANT ALL PRIVILEGES ON DATABASE nexus_accounts TO nexus;
GRANT ALL PRIVILEGES ON DATABASE nexus_transactions TO nexus;
GRANT ALL PRIVILEGES ON DATABASE nexus_fraud TO nexus;
GRANT ALL PRIVILEGES ON DATABASE nexus_ledger TO nexus;
GRANT ALL PRIVILEGES ON DATABASE nexus_kyc TO nexus;
GRANT ALL PRIVILEGES ON DATABASE nexus_ai_assistant TO nexus;
GRANT ALL PRIVILEGES ON DATABASE nexus_risk TO nexus;
GRANT ALL PRIVILEGES ON DATABASE nexus_saga TO nexus;
GRANT ALL PRIVILEGES ON DATABASE nexus_audit TO nexus;
```

### Install pgvector extension

Run in each database that uses pgvector (account, fraud, ledger, ai-assistant, audit):

```sql
-- Connect to each database and run:
\c nexus_accounts
CREATE EXTENSION IF NOT EXISTS vector;

\c nexus_fraud
CREATE EXTENSION IF NOT EXISTS vector;

\c nexus_ledger
CREATE EXTENSION IF NOT EXISTS vector;

\c nexus_ai_assistant
CREATE EXTENSION IF NOT EXISTS vector;

\c nexus_audit
CREATE EXTENSION IF NOT EXISTS vector;
```

**Important:** Use the `pgvector/pgvector:pg16` Docker image (not plain `postgres:16`) — it has the extension pre-built.

### Run the one-liner (if you have psql locally)

```bash
PGPASSWORD=nexus_dev_password psql -h localhost -U nexus -f postgres-init.sql
```

### MongoDB — Collections

MongoDB creates databases and collections automatically on first write. No pre-setup needed. The services connect with:

- `nexus_account` — account analytics documents
- `nexus_ledger` — CQRS read model
- `nexus_notification` — notification records
- `nexus_kyc` — KYC documents and decisions
- `nexus_audit` — audit records

MongoDB credentials: `nexus / nexus_dev_password` (auth source: `admin`)

---

## 11. Port Map

| Port | Container | Purpose |
|---|---|---|
| 3000 | nexus-grafana | Observability dashboards |
| 3100 | nexus-loki | Log aggregation |
| 4566 | nexus-localstack | AWS S3 + SQS emulation (dev only) |
| 5432 | nexus-postgres | PostgreSQL + pgvector |
| 6379 | nexus-redis | Cache + rate limiting |
| 8080 | nexus-api-gateway | Main entry point (all client traffic) |
| 8083 | nexus-identity-service | Auth + JWT |
| 8085 | nexus-account-service | Account management |
| 8086 | nexus-transaction-service | Transaction processing + Kafka Streams |
| 8087 | nexus-fraud-service | AI fraud detection |
| 8088 | nexus-ledger-service | Double-entry ledger |
| 8089 | nexus-notification-service | Push/email notifications |
| 8090 | nexus-ai-assistant-service | AI chat (RAG) |
| 8091 | nexus-ai-kyc-service | KYC document processing |
| 8092 | nexus-analytics-service | Business analytics |
| 8094 | nexus-risk-scoring-service | Risk scoring (GPT-4o) |
| 8095 | nexus-saga-orchestrator | SAGA pattern coordinator |
| 8096 | audit-write-native | Quarkus native audit writer |
| 8097 | nexus-audit-query-jvm | Audit search + AI query |
| 8190 | nexus-kafka-ui | Kafka browser UI |
| 8761 | nexus-discovery-service | Eureka service registry |
| 8888 | nexus-config-service | Spring Cloud Config server |
| 9090 | nexus-prometheus | Metrics scraping |
| 9200 | nexus-elasticsearch | Search + analytics store |
| 9411 | nexus-zipkin | Distributed tracing UI |
| 11434 | nexus-ollama | Local LLM (optional) |
| 19092 | nexus-kafka (external) | Kafka broker — HOST access |
| 27017 | nexus-mongodb | Document store |

---

## 12. Health Check URLs

```bash
# Infrastructure
curl http://localhost:9200/_cluster/health          # Elasticsearch
redis-cli -p 6379 ping                             # Redis
curl http://localhost:8190                          # Kafka UI

# Spring Cloud
curl http://localhost:8888/actuator/health          # Config Service
curl -u nexus-config:nexus-config-password \
     http://localhost:8888/nexus-account-service/docker  # Config content
curl http://localhost:8761/actuator/health          # Discovery Service
curl http://localhost:8761                          # Eureka Dashboard

# Services
curl http://localhost:8083/actuator/health          # Identity
curl http://localhost:8085/actuator/health          # Account
curl http://localhost:8086/actuator/health          # Transaction
curl http://localhost:8087/actuator/health          # Fraud
curl http://localhost:8088/actuator/health          # Ledger
curl http://localhost:8089/actuator/health          # Notification
curl http://localhost:8090/actuator/health          # AI Assistant
curl http://localhost:8091/actuator/health          # AI KYC
curl http://localhost:8092/actuator/health          # Analytics
curl http://localhost:8094/actuator/health          # Risk Scoring
curl http://localhost:8095/actuator/health          # Saga Orchestrator
curl http://localhost:8097/actuator/health          # Audit Query

# Gateway
curl http://localhost:8080/actuator/health          # API Gateway
curl http://localhost:8080/actuator/gateway/routes  # Registered routes

# Observability
curl http://localhost:9411                          # Zipkin UI
curl http://localhost:9090                          # Prometheus UI
curl http://localhost:3000                          # Grafana (admin/admin)
```

---

## 13. Troubleshooting

### Config Service fails to start

```
Caused by: java.lang.IllegalStateException: GIT_TOKEN must be set
```
**Fix:** `export GIT_TOKEN=ghp_your_token` before starting.

---

### Service fails with "Could not connect to config server"

```
Could not locate PropertySource: Could not resolve placeholder 'CONFIG_SERVER_URI'
```
**Fix:** Make sure `nexus-config-service` is running and healthy on port 8888 before starting any other service. The config service must be healthy (`/actuator/health` returns UP) before others start.

---

### Kafka connection refused in dev

```
org.apache.kafka.common.errors.TimeoutException: Topic not present
```
**Fix for dev mode:** The shared `kafka/docker-compose.yml` exposes Kafka on port `19092`, but dev profiles expect `localhost:9092`. Use the per-service docker-compose Kafka (which maps `9092:9092`) when running in dev mode.

---

### Identity service crashes — keystore not found

```
java.io.FileNotFoundException: /app/keys/nexus-identity.jks
```
**Fix:** Run the keytool command in Section 4.1 first, then try again.

---

### pgvector extension not found

```
ERROR: type "vector" does not exist
```
**Fix:** Use `pgvector/pgvector:pg16` image (not `postgres:16`), then run `CREATE EXTENSION IF NOT EXISTS vector;` in each database that needs it (see Section 10).

---

### Account service connects to wrong database

```
org.flywaydb.core.api.exception.FlywayValidateException: Found non-empty schema "public" without schema history table
```
**Fix:** The docker-compose.yml for account-service may still say `nexus_account`. Change `DATASOURCE_URL` and `POSTGRES_DB` in that file to `nexus_accounts` (plural). This was fixed in `application.yml` but the docker-compose environment override still had the old name.

---

### Service registered in Eureka with wrong IP

```
# In Eureka dashboard, IP shows as container IP but external calls fail
```
**Fix:** All services have `prefer-ip-address: true`. In Docker, they register with their container IP — which is correct for Docker-to-Docker calls. The gateway does `lb://service-name` lookups via Eureka, so container IPs are fine.

---

### Loki push errors in dev logs

```
ERROR c.g.l.l.Loki4jAppender — Cannot push to Loki: Connection refused
```
**Fix:** This is expected in dev. Logback is profile-gated: Loki appender only activates under `docker` or `production` profiles. If you see this in dev, a service's `logback-spring.xml` may not be correctly wrapped in `<springProfile name="docker,production">` — check it.

---

### Circuit breaker opens immediately in dev

```
io.github.resilience4j.circuitbreaker.CallNotPermittedException: CircuitBreaker is OPEN
```
**Fix:** The dev API gateway profile sets very lenient circuit breaker settings (100 failures before opening, 90% threshold). If it's still opening, increase `wait-duration-in-open-state` or `minimum-number-of-calls` in `application-dev.yml`.

---

### Kafka topics not created

```
WARN o.a.k.c.producer — Topic 'saga.commands' not present
```
**Fix:** The `kafka/docker-compose.yml` runs `nexus-kafka-topics` to create all 30 topics, but it waits for `nexus-kafka` to be healthy. Run:
```bash
docker compose -f kafka/docker-compose.yml logs nexus-kafka-topics
# If topics container exited, force re-run:
docker compose -f kafka/docker-compose.yml up nexus-kafka-topics
```

---

### OpenAI rate limit in risk-scoring

```
RateLimiter 'openai-risk' does not permit further calls
```
**Fix:** Normal behavior. The risk-scoring service self-limits to 55 requests/minute to avoid exceeding OpenAI's 60 RPM tier-1 limit. No action needed — requests queue and execute once a slot opens (30s timeout).

---

*End of tutorial. All configuration was analyzed from actual files — not from memory.*
