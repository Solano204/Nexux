# 11 — nexus-analytics-service
**Port:** 8092 | **Gateway base:** http://localhost:8080

## External Dependencies
- Kafka (port 19092) — Kafka Streams real-time aggregation (continuous background process)
- Redis (port 6380) — analytics cache + platform metrics
- OpenAI API — AI-generated insights endpoint only

## Kafka Topics Consumed (Kafka Streams — always running)
| Topic | Published by | What analytics does |
|---|---|---|
| `transactions.initiated` | nexus-transaction-service (Debezium) | Real-time: category totals, merchant totals, per-account spend |
| `transactions.completed` | nexus-saga-orchestrator (Debezium) | Marks transaction settled in stream aggregation |

REST endpoints only READ from aggregated state. No Kafka events published by REST calls.

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8092/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

---

### USER-FACING ENDPOINTS

### 2. Platform realtime metrics (no auth needed)
```
GET http://localhost:8080/api/v1/analytics/platform/realtime
```
Expected: 200 — real-time platform metrics

> **Kafka topics:** none
> **DB affected:**
> - Redis `analytics:platform:*` — HGETALL (continuously updated by Kafka Streams processor)

### 3. Monthly analytics for account
```
GET http://localhost:8080/api/v1/analytics/accounts/{accountId}/monthly/2026-06
Authorization: Bearer {accessToken}
```
Expected: 200 — monthly spending breakdown

> **Kafka topics:** none
> **DB affected:**
> - Redis `analytics:monthly:{accountId}:2026-06` — GET (cache hit → return)
> - Kafka Streams state store (RocksDB, in-process) — query monthly aggregation KTable (only on cache miss)
> - Redis `analytics:monthly:{accountId}:2026-06` — SET with TTL (only on cache miss)

### 4. Spending trends
```
GET http://localhost:8080/api/v1/analytics/accounts/{accountId}/trends
Authorization: Bearer {accessToken}
```
Expected: 200 — spending trend data

> **Kafka topics:** none
> **DB affected:**
> - Redis `analytics:trends:{accountId}` — GET (cache hit → return)
> - Kafka Streams state store (RocksDB) — query multiple time-window KTables for trend calculation (on cache miss)
> - Redis `analytics:trends:{accountId}` — SET with TTL (on cache miss)

### 5. Top merchants
```
GET http://localhost:8080/api/v1/analytics/accounts/{accountId}/merchants?limit=10
Authorization: Bearer {accessToken}
```
Expected: 200 — top N merchants by spend

> **Kafka topics:** none
> **DB affected:**
> - Redis `merchants:{accountId}` — ZREVRANGE 0 (limit-1) WITH SCORES (sorted set, updated live by Kafka Streams)

### 6. AI-generated insights (needs OpenAI)
```
GET http://localhost:8080/api/v1/analytics/accounts/{accountId}/insights/2026-06?language=es
Authorization: Bearer {accessToken}
```
Expected: 200 — list of AI-generated financial insights
Note: First call is slow (OpenAI generation). Subsequent calls are instant (Redis cache hit).

> **Kafka topics:** none
> **DB affected:**
> - Redis `analytics:insights:{accountId}:2026-06` — GET (cache hit → return immediately)
> - Redis `analytics:monthly:{accountId}:2026-06` + Kafka Streams state store — fetch spending data as prompt context (on cache miss)
> - OpenAI API — POST /v1/chat/completions (not streaming, JSON output with spending insights in `language`)
> - Redis `analytics:insights:{accountId}:2026-06` — SET with TTL (cache generated insights)

---

### INTERNAL ENDPOINTS (port 8092 directly — Kafka Streams state store queries)

### 7. Category spending from state store
```
GET http://localhost:8092/internal/v1/streams/category-spending?userId={userId}&category=FOOD&date=2026-06-21
```
Expected: 200 — category spending aggregate
categories: FOOD, TRANSPORT, ENTERTAINMENT, SHOPPING, HEALTH, UTILITIES, OTHER
Returns 503 if Kafka Streams is rebalancing — retry after a few seconds.

> **Kafka topics:** none
> **DB affected:**
> - Kafka Streams state store (RocksDB, in-process) — direct key lookup in `category-spending` KTable
> No Redis, no PostgreSQL, no external services.

### 8. Stream health / lag
```
GET http://localhost:8092/internal/v1/streams/health/lag
```
Expected: 200 — { state, lag }
State values: RUNNING, REBALANCING, NOT_RUNNING

> **Kafka topics:** none
> **DB affected:**
> - Kafka Streams internal metrics (in-memory StreamsMetrics) — reads thread state and consumer lag
> No Redis, no PostgreSQL, no external services.
