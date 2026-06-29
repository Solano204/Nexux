# 14 — audit-write-native (Quarkus Native)
**Port:** 8096 | **Stack:** Quarkus native binary

## External Dependencies
- Kafka (port 19092) — consumes ALL audit events from every service
- Elasticsearch (port 9201) — writes audit documents

## Kafka Topics Consumed (this is the ONLY input — no REST triggers)
| Topic | Published by | What gets indexed in Elasticsearch |
|---|---|---|
| `identity.events` | nexus-identity-service (Debezium) | Login, logout, register, password reset, session events |
| `identity.verified` / `identity.rejected` | nexus-identity-service (Debezium) | KYC approved/rejected events |
| `transactions.initiated` | nexus-transaction-service (Debezium) | Transaction initiated audit entry |
| `transactions.completed` | nexus-saga-orchestrator (Debezium) | Transaction completed audit entry |
| `transactions.failed` | nexus-transaction-service (Debezium) | Transaction failed audit entry |
| `fraud.flagged` | nexus-fraud-service (Debezium) | Fraud decisions, SAR filings, manual reviews |
| `accounts.created` | nexus-account-service (Debezium) | Account creation events |
| `account.frozen` | nexus-account-service (Debezium) | Account freeze events |
| `account.events` | nexus-account-service (Debezium) | Balance reserve, release, finalize events |
| `identity.kyc.result` | nexus-ai-kyc-service (Debezium) | KYC AI decisions, manual review outcomes |
| `ledger.posted` | nexus-ledger-service (Debezium) | Double-entry postings |
| `ledger.reversed` | nexus-ledger-service (Debezium) | Posting reversals |

All Kafka events land in Elasticsearch index `nexus-audit-*`, queryable via `nexus-audit-query-jvm`.

## Note
This service is a pure Kafka consumer — it has NO business REST endpoints.
To verify it works: create a transaction, then query Elasticsearch directly.

---

## Endpoint Testing Order

### 1. Health check (Quarkus — different path from Spring Boot)
```
GET http://localhost:8096/q/health
```
Expected: 200 — { status: "UP", checks: [...] }

> **Kafka topics:** none
> **DB affected:** connectivity probe only

### 2. Liveness
```
GET http://localhost:8096/q/health/live
```
Expected: 200 — { status: "UP" }

> **Kafka topics:** none
> **DB affected:** none — in-memory Quarkus liveness check

### 3. Readiness
```
GET http://localhost:8096/q/health/ready
```
Expected: 200 — { status: "UP" }

> **Kafka topics:** none
> **DB affected:**
> - Elasticsearch — connectivity check (readiness depends on ES being reachable)
> - Kafka — consumer group connectivity check

---

## Verify it's actually writing to Elasticsearch

After running identity, account, and transaction endpoints, audit events should be indexed.
Check directly via Elasticsearch:
```
GET http://localhost:9201/nexus-audit-*/_search
Content-Type: application/json

{
  "query": { "match_all": {} },
  "size": 5,
  "sort": [{ "@timestamp": { "order": "desc" } }]
}
```

> **Kafka topics:** none (this is a direct ES query, not through any service)
> **DB affected:**
> - Elasticsearch `nexus-audit-*` — read-only search query
