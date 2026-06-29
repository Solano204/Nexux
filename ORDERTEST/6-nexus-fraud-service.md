# 6 — nexus-fraud-service
**Port:** 8087 | **All endpoints are INTERNAL only**

## External Dependencies
- PostgreSQL (nexus_fraud DB on port 5433)
- Redis (port 6380) — merchant blacklist + account flags
- Kafka (port 19092) — consumes saga.commands, publishes saga.replies
- OpenAI API — AI fraud analysis agent (GPT-4 with tools)

## Variables to SAVE
- `{decisionId}` — from GET /decisions/{transactionId} response

## Kafka Events Consumed
| Topic | Command type | What fraud-service does |
|---|---|---|
| `saga.commands` (CheckFraudCommand) | From nexus-saga-orchestrator | Checks Redis blacklists + runs GPT-4 → publishes FraudApprovedReply or FraudRejectedReply |

## Kafka Events Published
| Topic | Event | Consumed by |
|---|---|---|
| `saga.replies` | FraudApprovedReply | nexus-saga-orchestrator → continues saga |
| `saga.replies` | FraudRejectedReply | nexus-saga-orchestrator (compensate) + nexus-transaction-service SagaReplyConsumer |
| `fraud.flagged` | FraudHighSeverityAlert | nexus-notification-service (FRAUD_ALERT) + audit-write-native |

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8087/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

### 2. Fraud metrics
```
GET http://localhost:8087/internal/v1/fraud/metrics
```
Expected: 200 — { lastHour: { total, approves, rejects, reviews }, pendingReviewCount, sarsFiledLast24h }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_fraud.fraud_decisions` — COUNT(*) GROUP BY decision, WHERE created_at > now()-1h

### 3. Get pending reviews
```
GET http://localhost:8087/internal/v1/fraud/decisions/pending-reviews
```
Expected: 200 — list of decisions with status REVIEW

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_fraud.fraud_decisions` — SELECT WHERE status = REVIEW ORDER BY created_at ASC

### 4. Direct fraud analysis (bypasses Kafka — for testing)
```
POST http://localhost:8087/internal/v1/fraud/analyze
Content-Type: application/json

{
  "transactionId": "{transactionId}",
  "userId": "{userId}",
  "sourceAccountId": "{accountId}",
  "targetAccountId": "{anotherAccountId}",
  "amount": 100.00,
  "currency": "MXN",
  "transactionType": "TRANSFER",
  "ipAddress": "192.168.1.1",
  "deviceFingerprint": "test-device-001"
}
```
Expected: 200 — { decision: APPROVE/REJECT/REVIEW, riskScore, reasoning }
Note: Requires OpenAI key. Without it returns 500.

> **Kafka topics:** none — this endpoint bypasses Kafka entirely, result is synchronous HTTP
> **DB affected:**
> - Redis `fraud:blacklist:merchants` — SISMEMBER (check merchantId)
> - Redis `fraud:blacklist:accounts` — SISMEMBER (check accountId)
> - OpenAI API — GPT-4 agent (velocity check tool + pattern analysis tool)
> - PostgreSQL `nexus_fraud.fraud_decisions` — INSERT (transactionId, userId, decision, riskScore, reasoning)

### 5. Get fraud decision by transaction — SAVE decisionId
```
GET http://localhost:8087/internal/v1/fraud/decisions/{transactionId}
```
Expected: 200 — full fraud decision record

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_fraud.fraud_decisions` — SELECT WHERE transaction_id

### 6. Get fraud decisions for a user (paginated)
```
GET http://localhost:8087/internal/v1/fraud/decisions/user/{userId}?page=0&size=10
```
Expected: 200 — paginated fraud decisions

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_fraud.fraud_decisions` — SELECT paginated WHERE user_id ORDER BY created_at DESC

### 7. Policy search
```
GET http://localhost:8087/internal/v1/fraud/policies/search?query=high+amount+transfer
```
Expected: 200 — policy search guidance (stub response)

> **Kafka topics:** none
> **DB affected:** none — stub endpoint, static response only

### 8. Blacklist a merchant
```
POST http://localhost:8087/internal/v1/fraud/merchants/blacklist/merchant-test-001
```
Expected: 200 — { merchantId, action: "BLACKLISTED", effectiveImmediately: true }

> **Kafka topics:** none
> **DB affected:**
> - Redis `fraud:blacklist:merchants` — SADD merchant-test-001 (effective immediately on next fraud check)

### 9. Remove merchant from blacklist
```
DELETE http://localhost:8087/internal/v1/fraud/merchants/blacklist/merchant-test-001
```
Expected: 200 — { merchantId, action: "REMOVED_FROM_BLACKLIST" }

> **Kafka topics:** none
> **DB affected:**
> - Redis `fraud:blacklist:merchants` — SREM merchant-test-001

### 10. Record manual review outcome
```
POST http://localhost:8087/internal/v1/fraud/review/{decisionId}/outcome
Content-Type: application/json

{
  "reviewerId": "{userId}",
  "outcome": "CLEARED",
  "notes": "Manual test review — verified legitimate"
}
```
Expected: 200 — { decisionId, outcome, reviewedAt }
outcome values: CONFIRMED_FRAUD, CLEARED, ESCALATED

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_fraud.fraud_decisions` — UPDATE status=outcome, reviewed_at=now()
> - PostgreSQL `nexus_fraud.fraud_reviews` — INSERT (decisionId, reviewerId, outcome, notes, reviewedAt)

### 11. File a SAR (Suspicious Activity Report)
```
POST http://localhost:8087/internal/v1/fraud/review/{decisionId}/sar
Content-Type: application/json

{
  "sarReference": "SAR-TEST-2026-001"
}
```
Expected: 200 — { decisionId, sarReference, sarFiledAt }

> **Kafka topics:** `fraud.flagged` (published via Debezium outbox — SAR filed event)
> **DB affected:**
> - PostgreSQL `nexus_fraud.fraud_decisions` — UPDATE sar_reference, sar_filed_at=now()
> - PostgreSQL `nexus_fraud.outbox` — INSERT (aggregate_type=fraud.flagged)
> **Reacted by:** audit-write-native — writes SAR event to Elasticsearch for compliance records
