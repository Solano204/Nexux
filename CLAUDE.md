# NEXUS Platform — Claude Code Context

## What this project is
Fintech microservices platform. 16 Spring Boot services + 1 Quarkus native service (audit-write-native) + infrastructure. Java 21, Maven multi-module, Docker Compose for prod.

## Build
```bash
# Build ALL services (from root — parent pom.xml exists)
mvn clean package -DskipTests -pl nexus-config-service,nexus-discovery-service,nexus-identity-service,nexus-account-service,nexus-transaction-service,nexus-fraud-service,nexus-ledger-service,nexus-notification-service,nexus-ai-assistant-service,nexus-ai-kyc-service,nexus-analytics-service,nexus-risk-scoring-service,nexus-saga-orchestrator,nexus-audit-query-jvm,nexus-api-gateway --also-make

# Build single service
mvn clean package -DskipTests -pl nexus-fraud-service
```

## Run (production)
```bash
docker compose -f docker-compose-prod.yml up -d
docker compose -f docker-compose-prod.yml down -v
```
Requires `.env` at root (passwords are in memory — never commit this file).

## Services & Ports (all on localhost in prod)

| Service | Port | Stack | Notes |
|---|---|---|---|
| nexus-api-gateway | 8080 | Spring Boot | Entry point for all requests |
| nexus-config-service | 8888 | Spring Boot | Must start first |
| nexus-discovery-service | 8761 | Spring Boot | Eureka — start second |
| nexus-identity-service | 8083 | Spring Boot | JWT auth, AWS KYC |
| nexus-account-service | 8085 | Spring Boot | Accounts + MongoDB |
| nexus-transaction-service | 8086 | Spring Boot | Kafka + ES |
| nexus-fraud-service | 8087 | Spring Boot | OpenAI + Kafka |
| nexus-ledger-service | 8088 | Spring Boot | Double-entry ledger |
| nexus-notification-service | 8089 | Spring Boot | Email/SMS via Kafka |
| nexus-ai-assistant-service | 8090 | Spring Boot | Ollama + OpenAI |
| nexus-ai-kyc-service | 8091 | Spring Boot | KYC + AWS Rekognition |
| nexus-analytics-service | 8092 | Spring Boot | Reports + OpenAI |
| nexus-risk-scoring-service | 8094 | Spring Boot | Risk scoring |
| nexus-saga-orchestrator | 8095 | Spring Boot | Saga pattern |
| nexus-audit-query-jvm | 8097 | Spring Boot | Query audit logs |
| audit-write-native | 8096 | Quarkus native | High-throughput audit writes |

## Infrastructure Ports (host → container)

| Service | Host Port | Notes |
|---|---|---|
| PostgreSQL | 5434 | 10 databases inside |
| MongoDB | 27019 | account service |
| Redis | 6381 | session cache |
| Kafka | 19093 | internal: nexus-kafka:9092 |
| Elasticsearch | 9202 | transactions + audit |
| Zipkin | 9413 | distributed tracing |
| Prometheus | 9093 | metrics |
| Grafana | 3002 | dashboards (admin UI) |
| Kafdrop | 9003 | Kafka UI |

## Critical rules
- NEVER commit `.env` — it contains real production passwords
- NEVER commit `secrets/nexus-identity.jks` — it is the JWT signing key
- `OPENAI_API_KEY` in `.env` is still a placeholder — AI features won't work until replaced
- Quarkus service (`audit-write-native`) uses its own Dockerfile under `src/main/docker/`
- Config service loads from Git: `nexus-config-service/nexus-platform-config/`
- Startup order: postgres/kafka → config → discovery → identity → all others → api-gateway

## Key env vars (see .env for values)
```
POSTGRES_PASSWORD, MONGO_PASSWORD, REDIS_PASSWORD
GRAFANA_PASSWORD, CONFIG_SERVER_PASSWORD
JWT_KEYSTORE_PASSWORD, GIT_TOKEN, OPENAI_API_KEY
```

## Health endpoints
All Spring Boot services: `GET /actuator/health`
Quarkus (audit-write-native): `GET /q/health`

## Context management tips
- Use /compact before switching between services
- Use /clear when jumping from backend to infra work
- Keep requests service-scoped: "fix nexus-fraud-service" not "fix the platform"
