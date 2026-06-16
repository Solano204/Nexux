# NEXUS Platform — Service Dependency Map

## Startup Order (hard dependencies)

```
1. Docker infra  (kafka/docker-compose.yml — always running)
   ├── PostgreSQL    :5432
   ├── MongoDB       :27017
   ├── Redis         :6379
   ├── Kafka         :19092
   ├── Elasticsearch :9200
   └── Zipkin        :9411

2. nexus-config-service     :8888   ← must be #1 Java service
3. nexus-discovery-service  :8761   ← must be #2 Java service

4. Everything else → any order
```

---

## HTTP Dependencies Between Services

| Service | Calls (HTTP) | Can run alone? |
|---|---|---|
| **nexus-api-gateway** | identity-service (JWT validation on every request) | ❌ needs identity up |
| **nexus-ai-assistant-service** | account-service, transaction-service, fraud-service | ❌ those 3 must be up |
| **nexus-identity-service** | nobody | ✅ yes |
| **nexus-account-service** | nobody | ✅ yes |
| **nexus-transaction-service** | nobody | ✅ yes |
| **nexus-fraud-service** | nobody | ✅ yes |
| **nexus-ledger-service** | nobody | ✅ yes |
| **nexus-notification-service** | nobody | ✅ yes |
| **nexus-ai-kyc-service** | nobody | ✅ yes |
| **nexus-analytics-service** | nobody | ✅ yes |
| **nexus-risk-scoring-service** | nobody | ✅ yes |
| **nexus-saga-orchestrator** | nobody (Kafka only) | ✅ yes |
| **nexus-audit-query-jvm** | nobody | ✅ yes |

---

## Kafka-Based Communication (async — no hard dependency)

Services communicate through Kafka topics. A consumer service does not need
its producer to be running — Kafka holds the messages until the consumer is ready.

| Producer | Topic | Consumer(s) |
|---|---|---|
| transaction-service | `transactions.initiated` | saga-orchestrator, analytics-service |
| transaction-service | `transactions.completed` | analytics-service, notification-service |
| transaction-service | `transactions.failed` | notification-service |
| saga-orchestrator | `saga.commands` | account-service, transaction-service, fraud-service, ledger-service |
| account-service | `saga.replies` | saga-orchestrator |
| fraud-service | `saga.replies` | saga-orchestrator |
| ledger-service | `saga.replies` | saga-orchestrator |
| fraud-service | `fraud.flagged` | notification-service, risk-scoring-service |
| fraud-service | `fraud.result` | saga-orchestrator |
| identity-service | `identity.verified` | ai-kyc-service, notification-service |
| identity-service | `users.registered` | account-service, notification-service |
| ledger-service | `ledger.posted` | analytics-service, audit-query-jvm |
| account-service | `accounts.created` | notification-service |
| risk-scoring-service | `risk.profile.updated` | fraud-service |

---

## The Rule

> **Most services talk through Kafka (async), not HTTP.**
> You can start and stop business services independently.
> Config service and Eureka should stay up, but everything else can come and go freely.

### In dev — minimum to test a feature

| What you want to test | Minimum services needed |
|---|---|
| Identity / login / JWT | identity-service |
| Account operations | identity-service + account-service |
| Full transaction flow | identity-service + account-service + transaction-service + fraud-service + ledger-service + saga-orchestrator |
| AI chat assistant | identity-service + account-service + transaction-service + fraud-service + ai-assistant-service |
| KYC pipeline | identity-service + ai-kyc-service |
| Notifications only | notification-service |
| Analytics / dashboards | analytics-service |

> Config service (:8888) and Eureka (:8761) are always required.
> Docker infra (Kafka, DBs, Redis) is always required.
