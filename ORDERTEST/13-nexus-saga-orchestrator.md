# 13 — nexus-saga-orchestrator
**Port:** 8095 | **All endpoints are INTERNAL only**

## External Dependencies
- PostgreSQL (nexus_saga DB on port 5433) — saga state + step history
- Kafka (port 19092) — saga command/reply channels

## Kafka Topics (automatic — driven by events, not REST)
| Consumed | Source |
|---|---|
| `transactions.initiated` (Debezium) | Starts TransferSaga |
| `users.registered` (Debezium) | Starts OnboardingSaga |
| `saga.replies` | Account / Ledger / Fraud replies |

| Published | When |
|---|---|
| `saga.commands` (CheckFraudCommand) | TransferSaga step 1 |
| `saga.commands` (PostLedgerCommand) | TransferSaga step 3 |
| `transactions.completed` (Debezium outbox) | TransferSaga step 5 — DONE |
| `saga.onboarding.complete` (Debezium outbox) | OnboardingSaga final step |

REST endpoints below are READ-ONLY — they do not publish Kafka events or modify DB.

## Full Transfer Saga Flow (for reference)
```
transactions.initiated
  → Step 1: saga.commands (CheckFraudCommand) → fraud-service → saga.replies (FraudApprovedReply)
  → Step 2: HTTP POST account-service /reserve → saga.replies (BalanceReservedReply)
  → Step 3: saga.commands (PostLedgerCommand) → ledger-service → saga.replies (LedgerPostedReply)
  → Step 4: HTTP POST account-service /finalize-transfer
  → Step 5: Debezium outbox → transactions.completed + saga.completed
```

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8095/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

### 2. Saga stats
```
GET http://localhost:8095/internal/v1/sagas/stats
```
Expected: 200 — { activeTransferSagas, activeOnboardingSagas, status: "OPERATIONAL" }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_saga.saga_instances` — COUNT(*) GROUP BY saga_type, status

### 3. Get stuck sagas
```
GET http://localhost:8095/internal/v1/sagas/stuck
```
Expected: 200 — { stuckTransferSagas: [...] }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_saga.saga_instances` — SELECT WHERE status NOT IN (COMPLETED, COMPENSATED, FAILED) AND expires_at < now()

### 4. Get transfer saga state
```
GET http://localhost:8095/internal/v1/sagas/transfer/{transactionId}
```
Expected: 200 or 404

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_saga.saga_instances` — SELECT WHERE correlation_id = transactionId AND saga_type = TRANSFER

### 5. Get transfer saga step history
```
GET http://localhost:8095/internal/v1/sagas/transfer/{transactionId}/history
```
Expected: 200 — ordered list of saga steps with timestamps and outcomes

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_saga.saga_step_events` — SELECT WHERE saga_id (derived from transactionId), ORDER BY occurred_at ASC

### 6. Get onboarding saga state
```
GET http://localhost:8095/internal/v1/sagas/onboarding/{userId}
```
Expected: 200 or 404

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_saga.saga_instances` — SELECT WHERE correlation_id = userId AND saga_type = ONBOARDING
