# 4 — nexus-account-service
**Port:** 8085 | **Gateway base:** http://localhost:8080

## External Dependencies
- PostgreSQL (nexus_accounts DB on port 5433) — accounts + pgvector RAG
- MongoDB (port 27018) — event store + analytics
- Redis (port 6380) — balance cache
- Kafka (port 19092) — saga commands consumer
- OpenAI API — advisor/chat and advisor/insights only

## Variables to SAVE
- `{accountId}` — from GET /accounts response

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8085/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

---

### USER-FACING ENDPOINTS

```
Authorization: Bearer {accessToken}
```

### 2. List my accounts — SAVE accountId
```
GET http://localhost:8080/api/v1/accounts
Authorization: Bearer {accessToken}
```
Expected: 200 — array of accounts. Save `accountId`.

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_accounts.accounts` — SELECT WHERE user_id (from JWT)

### 3. Account detail
```
GET http://localhost:8080/api/v1/accounts/{accountId}
Authorization: Bearer {accessToken}
```
Expected: 200 — full account detail

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_accounts.accounts` — SELECT WHERE id = accountId

### 4. Balance (Redis cache)
```
GET http://localhost:8080/api/v1/accounts/{accountId}/balance
Authorization: Bearer {accessToken}
```
Expected: 200 — { balance, currency }
Returns 503 + Retry-After: 1 on cache miss. Retry after 1 second.

> **Kafka topics:** none
> **DB affected:**
> - Redis `balance:{accountId}` — GET (cache hit → return immediately)
> - PostgreSQL `nexus_accounts.accounts` — SELECT available_balance (only on cache miss)
> - Redis `balance:{accountId}` — SET with TTL (only on cache miss, to populate cache)

### 5. Event history (paginated)
```
GET http://localhost:8080/api/v1/accounts/{accountId}/events?page=0&size=20
Authorization: Bearer {accessToken}
```
Expected: 200 — paginated list of account events

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_accounts.account_events` — find paginated WHERE accountId, sorted by timestamp DESC

### 6. MongoDB analytics
```
GET http://localhost:8080/api/v1/accounts/{accountId}/analytics
Authorization: Bearer {accessToken}
```
Expected: 200 or 404 if no analytics data yet

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_accounts.account_analytics` — findOne WHERE accountId

### 7. AI Advisor chat (SSE — needs OpenAI)
```
POST http://localhost:8080/api/v1/accounts/{accountId}/advisor/chat
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "message": "How much did I spend last month?",
  "sessionId": "test-session-001"
}
```
Expected: text/event-stream — tokens stream in real time

> **Kafka topics:** none
> **DB affected:**
> - Redis `conversation:{sessionId}` — LRANGE (load session history as prompt context)
> - PostgreSQL `nexus_accounts.account_embeddings` — vector similarity search (pgvector) — SELECT top-K relevant chunks as RAG context
> - OpenAI API — POST /v1/chat/completions (stream: true)
> - PostgreSQL `nexus_accounts.account_embeddings` — INSERT embedding of new turn
> - Redis `conversation:{sessionId}` — LPUSH new turn + LTRIM to keep last N

### 8. AI Advisor insights (needs OpenAI)
```
GET http://localhost:8080/api/v1/accounts/{accountId}/advisor/insights
Authorization: Bearer {accessToken}
```
Expected: 200 — structured financial advice JSON

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_accounts.account_embeddings` — vector search (pgvector) for context
> - OpenAI API — POST /v1/chat/completions (not streaming, structured JSON output)

---

### INTERNAL ENDPOINTS (port 8085 directly)

### 9. Create default accounts for a user
```
POST http://localhost:8085/internal/api/v1/accounts/create-defaults
Content-Type: application/json

{
  "userId": "{userId}",
  "currency": "MXN"
}
```
Expected: 201 — { success: true, accountsCreated: 2, accounts: [...] }

> **Kafka topics:** `accounts.created` (published via Debezium outbox)
> **DB affected:**
> - PostgreSQL `nexus_accounts.accounts` — INSERT x2 (checking account + savings account, status=ACTIVE)
> - PostgreSQL `nexus_accounts.outbox` — INSERT (aggregate_type=accounts.created)
> **Reacted by:** audit-write-native — writes account creation to Elasticsearch

### 10. Balance check (direct PostgreSQL, no cache)
```
GET http://localhost:8085/internal/api/v1/accounts/{accountId}/balance-check
```
Expected: 200 — { availableBalance, reservedAmount, totalBalance, currency, status, dailyLimitRemaining }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_accounts.accounts` — SELECT balance fields directly (bypasses Redis cache)

### 11. Get accounts by user
```
GET http://localhost:8085/internal/api/v1/accounts/by-user/{userId}
```
Expected: 200 — list of account summaries

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_accounts.accounts` — SELECT WHERE user_id

### 12. Reserve balance (saga step)
```
POST http://localhost:8085/internal/api/v1/accounts/{accountId}/reserve
Content-Type: application/json

{
  "transactionId": "00000000-0000-0000-0000-000000000001",
  "amount": 100.00
}
```
Expected: 200 — { success: true, reservedAmount, newAvailableBalance }

> **Kafka topics:** `saga.replies` — `BalanceReservedReply` (success) or `BalanceReservationFailedReply` (insufficient funds) — via Debezium outbox
> **DB affected:**
> - PostgreSQL `nexus_accounts.accounts` — UPDATE (available_balance -= amount, reserved_amount += amount)
> - PostgreSQL `nexus_accounts.balance_reservations` — INSERT (transactionId, accountId, amount)
> - PostgreSQL `nexus_accounts.outbox` — INSERT (aggregate_type=saga.replies, replyType=BalanceReservedReply)
> - Redis `balance:{accountId}` — DEL (invalidate cache after balance change)
> **Reacted by:** nexus-saga-orchestrator receives reply → sends PostLedgerCommand to ledger-service

### 13. Release reserved balance (saga compensation)
```
POST http://localhost:8085/internal/api/v1/accounts/{accountId}/release
Content-Type: application/json

{
  "transactionId": "00000000-0000-0000-0000-000000000001",
  "amount": 100.00
}
```
Expected: 200 — { success: true, releasedAmount }

> **Kafka topics:** `account.events` (published via Debezium outbox — BalanceReleased, audit only)
> **DB affected:**
> - PostgreSQL `nexus_accounts.accounts` — UPDATE (reserved_amount -= amount, available_balance += amount)
> - PostgreSQL `nexus_accounts.balance_reservations` — DELETE WHERE transactionId
> - PostgreSQL `nexus_accounts.outbox` — INSERT (aggregate_type=account.events)
> - Redis `balance:{accountId}` — DEL (invalidate cache)

### 14. Finalize transfer (saga final step)
```
POST http://localhost:8085/internal/api/v1/accounts/finalize-transfer
Content-Type: application/json

{
  "sourceAccountId": "{accountId}",
  "targetAccountId": "{anotherAccountId}",
  "transactionId": "00000000-0000-0000-0000-000000000002",
  "amount": 50.00
}
```
Expected: 200 — { success: true, transactionId }

> **Kafka topics:** `account.events` (published via Debezium outbox — BalanceSettled, audit only)
> **DB affected:**
> - PostgreSQL `nexus_accounts.accounts` — UPDATE source (reserved_amount -= amount, committed_debit += amount)
> - PostgreSQL `nexus_accounts.accounts` — UPDATE target (available_balance += amount, committed_credit += amount)
> - PostgreSQL `nexus_accounts.balance_reservations` — DELETE WHERE transactionId
> - PostgreSQL `nexus_accounts.outbox` — INSERT (aggregate_type=account.events)
> - Redis `balance:{sourceAccountId}` — DEL (invalidate cache)
> - Redis `balance:{targetAccountId}` — DEL (invalidate cache)

### 15. Freeze account
```
POST http://localhost:8085/internal/api/v1/accounts/{accountId}/freeze
Content-Type: application/json

{
  "reason": "MANUAL_TEST_FREEZE"
}
```
Expected: 200 — { success: true, status: "FROZEN" }

> **Kafka topics:** `account.frozen` (published via Debezium outbox)
> **DB affected:**
> - PostgreSQL `nexus_accounts.accounts` — UPDATE status=FROZEN, frozen_reason, frozen_at
> - PostgreSQL `nexus_accounts.outbox` — INSERT (aggregate_type=account.frozen)
> **Reacted by:** audit-write-native (Elasticsearch), nexus-risk-scoring-service (flags user)

### 16. Unfreeze account
```
POST http://localhost:8085/internal/api/v1/accounts/{accountId}/unfreeze
```
Expected: 200 — { success: true, status: "ACTIVE" }

> **Kafka topics:** `account.events` (published via Debezium outbox — AccountUnfrozen, audit only)
> **DB affected:**
> - PostgreSQL `nexus_accounts.accounts` — UPDATE status=ACTIVE, unfrozen_at
> - PostgreSQL `nexus_accounts.outbox` — INSERT (aggregate_type=account.events)
