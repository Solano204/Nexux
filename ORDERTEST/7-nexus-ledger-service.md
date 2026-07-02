# 7 — nexus-ledger-service
**Port:** 8088 | **Gateway base:** http://localhost:8080

## External Dependencies
- PostgreSQL (nexus_ledger DB on port 5433) — double-entry ledger + pgvector
- Kafka (port 19092) — consumes saga.commands (PostLedgerCommand)
- OpenAI API — /explain SSE endpoint only

## Kafka Events Consumed
| Topic | Command type | What ledger-service does |
|---|---|---|
| `saga.commands` (PostLedgerCommand) | From nexus-saga-orchestrator | Posts debit + credit entries, publishes LedgerPostedReply |

## Kafka Events Published
| Topic | Event | Consumed by |
|---|---|---|
| `saga.replies` | LedgerPostedReply | nexus-saga-orchestrator (step 4: finalize-transfer) + nexus-transaction-service SagaReplyConsumer (marks COMPLETED) |
| `ledger.posted` | LedgerPosted | audit-write-native (Elasticsearch) |
| `ledger.reversed` | LedgerReversed | audit-write-native (Elasticsearch) |

## Variables to SAVE
- `{postingId}` — from internal POST /postings/manual response

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8088/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

---

### USER-FACING ENDPOINTS

### 2. Ledger balance for account
```
GET http://localhost:8080/api/v1/ledger/accounts/{accountId}/balance
Authorization: Bearer {accessToken}
```
Expected: 200 — { accountId, balance, currency: "MXN" }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_ledger.ledger_entries` — SELECT SUM(amount) WHERE account_id, grouped by entry_type (CREDIT - DEBIT)

### 3. Ledger entries (full history, paginated)
```
GET http://localhost:8080/api/v1/ledger/accounts/{accountId}/entries?page=0&size=20
Authorization: Bearer {accessToken}
```
Expected: 200 — paginated ledger entries

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_ledger.ledger_entries` — SELECT paginated WHERE account_id, ORDER BY created_at DESC

### 4. Monthly summary
```
GET http://localhost:8080/api/v1/ledger/accounts/{accountId}/summary/monthly?year=2026&month=6
Authorization: Bearer {accessToken}
```
Expected: 200 — monthly totals (debits, credits, net)

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_ledger.ledger_entries` — SELECT SUM(amount) GROUP BY entry_type WHERE account_id AND created_at BETWEEN month start/end

### 5. Transaction posting detail
```
GET http://localhost:8080/api/v1/ledger/transactions/{transactionId}/posting
Authorization: Bearer {accessToken}
```
Expected: 200 — double-entry posting, or 404 if not posted yet

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_ledger.postings` — SELECT WHERE transaction_id
> - PostgreSQL `nexus_ledger.ledger_entries` — SELECT WHERE posting_id (JOIN to get both debit + credit entries)

### 6. AI ledger explainer (SSE — needs OpenAI)
```
POST http://localhost:8080/api/v1/ledger/accounts/{accountId}/explain
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "message": "Why did my balance change this month?",
  "sessionId": "ledger-explain-001"
}
```
Expected: text/event-stream — AI explanation streams token by token

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_ledger.ledger_embeddings` — vector similarity search (pgvector) — SELECT top-K relevant ledger entries as context
> - OpenAI API — POST /v1/chat/completions (stream: true)

---

### INTERNAL ENDPOINTS (port 8088 directly)

### 7. Ledger balance (authoritative, no cache)
```
GET http://localhost:8088/internal/v1/ledger/accounts/{accountId}/balance
```
Expected: 200 — { accountId, ledgerBalance, currency, source: "ledger-entries" }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_ledger.ledger_entries` — SELECT SUM(amount) WHERE account_id (direct, no Redis cache)

### 8. Reconciliation status
```
GET http://localhost:8088/internal/v1/ledger/reconciliation/status
```
Expected: 200 — { status: "OK", lastRunAt, note }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_ledger.reconciliation_runs` — SELECT latest record ORDER BY run_at DESC LIMIT 1

### 9. Integrity verification
```
GET http://localhost:8088/internal/v1/ledger/integrity/verify
```
Expected: 200 — { status: "VERIFICATION_COMPLETE", verifiedAt }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_ledger.ledger_entries` — FULL SCAN, recalculates running checksums across all entries
> May be slow on large datasets. Read-only — no writes.

### 10. Manual adjustment posting (ADMIN) — SAVE postingId
```
POST http://localhost:8088/internal/v1/ledger/postings/manual
Content-Type: application/json

{
  "sourceAccountId": "{accountId}",
  "targetAccountId": "{anotherAccountId}",
  "amount": 10.00,
  "currency": "MXN",
  "reason": "TEST_MANUAL_ADJUSTMENT",
  "approvalReference": "APPROVAL-TEST-001"
}
```
Expected: 201 — { postingId, debitEntryId, creditEntryId }

> **Kafka topics:** `ledger.posted` (published via Debezium outbox)
> **DB affected:**
> - PostgreSQL `nexus_ledger.postings` — INSERT (postingId, transactionId=null, type=MANUAL, approvalReference)
> - PostgreSQL `nexus_ledger.ledger_entries` — INSERT x2 (DEBIT for source + CREDIT for target, linked to postingId)
> - PostgreSQL `nexus_ledger.outbox` — INSERT (aggregate_type=ledger.posted)
> **Reacted by:** audit-write-native — writes manual adjustment to Elasticsearch (compliance traceability)

### 11. Reverse a posting
```
POST http://localhost:8088/internal/v1/ledger/postings/{postingId}/reverse
Content-Type: application/json

{
  "reason": "TEST_REVERSAL"
}
```
Expected: 200 — { originalPostingId, reversalPostingId, debitEntryId, creditEntryId }

> **Kafka topics:** `ledger.reversed` (published via Debezium outbox)
> **DB affected:**
> - PostgreSQL `nexus_ledger.postings` — INSERT reversal posting (type=REVERSAL, reverses_posting_id=postingId) + UPDATE original posting reversed=true
> - PostgreSQL `nexus_ledger.ledger_entries` — INSERT x2 (mirror entries with opposite signs: CREDIT for source + DEBIT for target)
> - PostgreSQL `nexus_ledger.outbox` — INSERT (aggregate_type=ledger.reversed)
> **Reacted by:** audit-write-native — writes reversal to Elasticsearch

### 12. Reconstruct balance from entries
```
POST http://localhost:8088/internal/v1/ledger/accounts/{accountId}/reconstruct
```
Expected: 200 — { accountId, reconstructedBalance, source: "ledger-entry-running-balance" }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_ledger.ledger_entries` — SELECT ALL WHERE account_id, recalculates running sum from scratch
> Read-only — no writes. Use after a suspected data inconsistency.
