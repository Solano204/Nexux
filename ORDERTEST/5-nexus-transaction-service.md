# 5 — nexus-transaction-service
**Port:** 8086 | **Gateway base:** http://localhost:8080

## External Dependencies
- PostgreSQL (nexus_transactions DB on port 5433)
- Elasticsearch (port 9201) — full-text transaction search
- Kafka (port 19092) — publishes transaction events, triggers saga

## Variables to SAVE
- `{transactionId}` — from POST /transfer response

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8086/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

---

### USER-FACING ENDPOINTS

```
Authorization: Bearer {accessToken}
```

### 2. Initiate transfer — SAVE transactionId
```
POST http://localhost:8080/api/v1/transactions/transfer
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "sourceAccountId": "{sourceAccountId}",
  "targetAccountId": "{targetAccountId}",
  "amount": 100.00,
  "currency": "MXN",
  "description": "Test transfer"
}
```
Expected: 202 — { transactionId, status: "INITIATED" }

> **Kafka topics:** `transactions.initiated` (published via Debezium outbox)
> **Cascade topics:** `transactions.initiated` → `saga.commands` (CheckFraudCommand) → `saga.replies` (FraudApprovedReply) → `saga.commands` (ReserveBalanceCommand) → `saga.replies` (BalanceReservedReply) → `saga.commands` (PostLedgerCommand) → `saga.replies` (LedgerPostedReply) → `transactions.completed`
> **DB affected:**
> - PostgreSQL `nexus_transactions.transactions` — INSERT (transactionId, sourceAccountId, targetAccountId, amount, currency, status=INITIATED, sagaId)
> - PostgreSQL `nexus_transactions.outbox` — INSERT (aggregate_type=transactions.initiated)
> - Elasticsearch `nexus-transactions-*` — async INDEX document (happens after Kafka consumer picks it up)
> **Reacted by:** nexus-saga-orchestrator (TransferSaga), nexus-fraud-service, nexus-analytics-service (Kafka Streams), nexus-risk-scoring-service, audit-write-native

### 3. Initiate payment
```
POST http://localhost:8080/api/v1/transactions/payment
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "sourceAccountId": "{sourceAccountId}",
  "targetAccountId": "{targetAccountId}",
  "amount": 50.00,
  "currency": "MXN",
  "description": "Test payment",
  "merchantId": "merchant-001"
}
```
Expected: 202 — { transactionId, status: "INITIATED" }

> **Kafka topics:** `transactions.initiated` (published via Debezium outbox — same topic as transfer)
> **Cascade topics:** same cascade as transfer above
> **DB affected:**
> - PostgreSQL `nexus_transactions.transactions` — INSERT (same as transfer, plus merchantId column)
> - PostgreSQL `nexus_transactions.outbox` — INSERT (aggregate_type=transactions.initiated)
> - Elasticsearch `nexus-transactions-*` — async INDEX
> Note: fraud-service checks `merchantId` against Redis `fraud:blacklist:merchants` before AI analysis

### 4. Get transaction history (paginated)
```
GET http://localhost:8080/api/v1/transactions?page=0&size=10
Authorization: Bearer {accessToken}
```
Expected: 200 — page of transactions

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_transactions.transactions` — SELECT paginated WHERE user_id FROM JWT, ORDER BY created_at DESC

### 5. Get specific transaction
```
GET http://localhost:8080/api/v1/transactions/{transactionId}
Authorization: Bearer {accessToken}
```
Expected: 200 — full transaction detail including current saga status

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_transactions.transactions` — SELECT WHERE id = transactionId

### 6. Search transactions (Elasticsearch)
```
GET http://localhost:8080/api/v1/transactions/search?query=test
Authorization: Bearer {accessToken}
```
Expected: 200 — list of matching transactions
Note: Requires Elasticsearch running and transaction indexed (may take seconds).

> **Kafka topics:** none
> **DB affected:**
> - Elasticsearch `nexus-transactions-*` — full-text query (no PostgreSQL hit)

---

### INTERNAL ENDPOINTS (port 8086 directly)

### 7. Get transaction status
```
GET http://localhost:8086/internal/v1/transactions/{transactionId}/status
```
Expected: 200 — { transactionId, status, sagaId, sagaStep, isTerminal }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_transactions.transactions` — SELECT status, saga_id, saga_step WHERE id

### 8. Get active transactions for an account
```
GET http://localhost:8086/internal/v1/accounts/{accountId}/transactions/active
```
Expected: 200 — list of in-flight transactions

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_transactions.transactions` — SELECT WHERE (source_account_id OR target_account_id) = accountId AND status NOT IN (COMPLETED, FAILED, REJECTED)

### 9. Get transaction metrics
```
GET http://localhost:8086/internal/v1/transactions/metrics
```
Expected: 200 — { total, initiated, completed, failed, fraudRejected, activeInFlight }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_transactions.transactions` — COUNT(*) GROUP BY status (aggregate query)

### 10. Force compensate a stuck transaction (admin)
```
POST http://localhost:8086/internal/v1/transactions/{transactionId}/force-compensate
```
Expected: 200 — { success: true/false, newStatus }

> **Kafka topics:** `saga.commands` (CompensateCommand published via Debezium outbox)
> **Cascade topics:** `saga.commands` → nexus-saga-orchestrator → calls account-service /release → `account.events` → marks transaction FAILED → `transactions.failed`
> **DB affected:**
> - PostgreSQL `nexus_transactions.transactions` — UPDATE status=COMPENSATING
> - PostgreSQL `nexus_transactions.outbox` — INSERT (aggregate_type=saga.commands, command=CompensateCommand)
> **Reacted by:** nexus-saga-orchestrator (compensation chain), nexus-notification-service (TRANSACTION_FAILED notification)
