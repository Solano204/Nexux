# 12 — nexus-risk-scoring-service
**Port:** 8094 | **All endpoints are INTERNAL only**

## External Dependencies
- PostgreSQL (nexus_risk DB on port 5433) — risk profiles
- Redis (port 6380) — risk tier cache
- OpenAI API — AI risk scoring agent
- Kafka (port 19092) — consumes transaction events

## Kafka Topics Consumed (automatic — no REST trigger)
| Topic | Published by | What risk-service does |
|---|---|---|
| `transactions.initiated` | nexus-transaction-service (Debezium) | Flags user for recomputation |
| `transactions.completed` | nexus-saga-orchestrator (Debezium) | Finalizes transaction data |

No Kafka events published by REST endpoints.

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8094/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

### 2. Platform risk stats
```
GET http://localhost:8094/internal/v1/risk/stats
```
Expected: 200 — { veryLow, low, medium, high, veryHigh, candidatesForRecomputation }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_risk.risk_profiles` — COUNT(*) GROUP BY risk_tier (aggregate query)

### 3. Get risk profile for user
```
GET http://localhost:8094/internal/v1/risk/profiles/{userId}
```
Expected: 200 or 404 if not yet computed

> **Kafka topics:** none
> **DB affected:**
> - Redis `risk:profile:{userId}` — GET (cache hit → return)
> - PostgreSQL `nexus_risk.risk_profiles` — SELECT latest WHERE user_id ORDER BY computed_at DESC LIMIT 1 (on cache miss)

### 4. Get risk tier (Redis cache first)
```
GET http://localhost:8094/internal/v1/risk/profiles/{userId}/tier
```
Expected: 200 — { userId, riskTier }
Values: VERY_LOW, LOW, MEDIUM, HIGH, VERY_HIGH, UNKNOWN

> **Kafka topics:** none
> **DB affected:**
> - Redis `risk:tier:{userId}` — GET (fast path — checked by fraud-service before AI analysis)
> - PostgreSQL `nexus_risk.risk_profiles` — SELECT risk_tier WHERE user_id ORDER BY computed_at DESC LIMIT 1 (only on cache miss)

### 5. Get profile history
```
GET http://localhost:8094/internal/v1/risk/profiles/{userId}/history
```
Expected: 200 — list of risk profiles ordered by computedAt DESC

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_risk.risk_profiles` — SELECT WHERE user_id ORDER BY computed_at DESC

### 6. Get batch job status
```
GET http://localhost:8094/internal/v1/risk/batch/status
```
Expected: 200 — batch job info

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_risk.batch_runs` — SELECT latest ORDER BY started_at DESC LIMIT 1

### 7. Trigger manual risk computation for user (needs OpenAI)
```
POST http://localhost:8094/internal/v1/risk/profiles/{userId}/compute
```
Expected: 200 — { status: "COMPUTED", userId, overallRiskScore, riskTier, confidence }
Note: Requires OPENAI_API_KEY. Without it returns 500.

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_risk.risk_profiles` — SELECT all previous profiles for userId (gives AI historical context)
> - OpenAI API — POST /v1/chat/completions: agent analyzes patterns, returns JSON with riskScore + riskTier + confidence
> - PostgreSQL `nexus_risk.risk_profiles` — INSERT new computed profile (overallRiskScore, riskTier, confidence, computedAt)
> - Redis `risk:tier:{userId}` — SET (cache new tier)
> - Redis `risk:profile:{userId}` — SET with TTL (cache full profile)

### 8. Trigger manual batch run
```
POST http://localhost:8094/internal/v1/risk/batch/trigger
```
Expected: 200 — batch job trigger response
Note: Runs async. Check /batch/status after.

> **Kafka topics:** none
> **DB affected (synchronous — immediate):**
> - PostgreSQL `nexus_risk.batch_runs` — INSERT (status=STARTED, started_at=now())
> **DB affected (async — background job runs for each user due for recomputation):**
> - PostgreSQL `nexus_risk.risk_profiles` — SELECT users flagged for recomputation
> - OpenAI API — one call per user
> - PostgreSQL `nexus_risk.risk_profiles` — INSERT new profiles as they complete
> - Redis — UPDATE tier + profile cache for each recomputed user
> - PostgreSQL `nexus_risk.batch_runs` — UPDATE status=COMPLETED, completed_at when done
