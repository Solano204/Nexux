# nexus-transaction-service — Complete Dependency Map

---

## Infrastructure transaction service REQUIRES (direct connections)

| Component         | Port  | Why                                                                                     |
|-------------------|-------|-----------------------------------------------------------------------------------------|
| PostgreSQL        | 5433  | nexus_transactions DB — transactions table + outbox table                               |
| Elasticsearch     | 9202  | full-text search index of transactions (GET /search endpoint)                           |
| Kafka             | 19092 | event publishing + consuming + Kafka Streams topologies                                 |
| Config service    | 8888  | loads all config on startup                                                             |
| Discovery service | 8761  | Eureka registration                                                                     |

**NO Redis, NO MongoDB, NO S3, NO SQS, NO OpenAI — transaction service has ZERO direct dependency on these.**

---

## Kafka topics this service PRODUCES to

| Topic                       | Consumed by                                      | When                                      |
|-----------------------------|--------------------------------------------------|-------------------------------------------|
| transactions.initiated      | fraud-service, Kafka Streams velocity topology   | immediately after POST /transfer or /payment |
| saga.commands               | account-service, fraud-service, ledger-service   | ReserveBalanceCommand, FraudCheckCommand, PostLedgerCommand |
| transactions.completed      | notification-service, analytics-service, risk-scoring-service, Kafka Streams merchant topology | when saga reaches COMPLETED |
| transactions.failed         | notification-service, analytics-service          | on FRAUD_REJECTED or balance/ledger failure |
| transactions.velocity       | fraud-service (via Kafka Streams output)         | every 5-min window from VelocityTopology  |
| transactions.merchant-stats | analytics-service (via Kafka Streams output)     | every 1-hour window from MerchantAggregationTopology |

---

## Kafka topics this service CONSUMES from

| Topic        | Group ID                               | Sent by                              | What it does                                          |
|--------------|----------------------------------------|--------------------------------------|-------------------------------------------------------|
| fraud.result | transaction-service-fraud-results      | fraud-service (direct delivery)      | processes CLEARED/REJECTED decision, advances saga    |
| saga.replies | transaction-service-saga-replies       | account-service, ledger-service, fraud-service | routes BalanceReservedReply, LedgerPostedReply, FraudRejectedReply |

---

## Kafka Streams topologies (run inside this service)

| Topology                   | Input topic             | Output topic                | Window    | What it computes                                    |
|----------------------------|-------------------------|-----------------------------|-----------|-----------------------------------------------------|
| TransactionVelocityTopology | transactions.initiated  | transactions.velocity       | 5 minutes | count, totalAmount, avgAmount, maxAmount per userId |
| MerchantAggregationTopology | transactions.completed  | transactions.merchant-stats | 1 hour    | transactionCount, totalVolume, avgTransaction per merchantName |

---

## Services that call transaction (it is the dependency)

| Caller                     | Endpoint called                                             | Why                                         |
|----------------------------|-------------------------------------------------------------|---------------------------------------------|
| nexus-saga-orchestrator    | GET /internal/v1/transactions/{id}/status                   | saga state recovery on restart              |
| nexus-fraud-service        | GET /internal/v1/transactions/{id}/status                   | alternative fraud result delivery path      |
| nexus-account-service      | GET /internal/v1/accounts/{accountId}/transactions/active   | verify no in-flight txns before account closure |
| nexus-health-monitor-lambda | GET /internal/v1/transactions/metrics                      | platform health monitoring                  |

---

## Services transaction calls outbound (via Kafka only — no HTTP calls)

| Service               | Via topic       | Command / event type                           |
|-----------------------|-----------------|------------------------------------------------|
| nexus-fraud-service   | saga.commands   | FraudCheckCommand (triggered by transactions.initiated) |
| nexus-account-service | saga.commands   | ReserveBalanceCommand, ReleaseBalanceCommand   |
| nexus-ledger-service  | saga.commands   | PostLedgerCommand (via saga orchestrator)      |
| nexus-notification-service | transactions.completed, transactions.failed | transaction result notification |
| nexus-analytics-service | transactions.completed, transactions.failed | analytics ingestion |
| nexus-risk-scoring-service | transactions.completed | risk profile update |

---

## Complete transaction saga flow (step by step)

```
1. POST /api/v1/transactions/transfer
      → save to Postgres (status=INITIATED)
      → index to Elasticsearch (async)
      → publish to transactions.initiated
      → return 202 ACCEPTED immediately

2. fraud-service consumes transactions.initiated
      → runs AI fraud check (uses OpenAI)
      → publishes to fraud.result OR saga.replies(FraudRejectedReply)

3. transaction-service consumes fraud.result
      → if CLEARED  → status=FRAUD_CLEARED → publish ReserveBalanceCommand to saga.commands
      → if REJECTED → status=FRAUD_REJECTED → status=FAILED → publish to transactions.failed

4. account-service consumes ReserveBalanceCommand
      → reserves balance in account
      → publishes BalanceReservedReply OR BalanceReservationFailedReply to saga.replies

5. transaction-service consumes BalanceReservedReply
      → status=BALANCE_RESERVED
      → saga orchestrator sends PostLedgerCommand to saga.commands

6. ledger-service consumes PostLedgerCommand
      → creates double-entry ledger posting
      → publishes LedgerPostedReply to saga.replies

7. transaction-service consumes LedgerPostedReply
      → status=COMPLETED
      → publish to transactions.completed
      → re-index to Elasticsearch

8. notification-service consumes transactions.completed → sends push/email
   analytics-service consumes transactions.completed  → updates reports
   risk-scoring-service consumes transactions.completed → updates risk score
   MerchantAggregationTopology aggregates merchant stats

9. audit-write-native (via CDC / Kafka)
      → writes all state changes to MongoDB + Elasticsearch audit index
```

---

## Indirect dependencies (not owned by transaction service, but hit during the flow)

| Component     | Indirect role                                                                                      |
|---------------|----------------------------------------------------------------------------------------------------|
| MongoDB       | audit-write-native writes every transaction state change to nexus_audit collection                 |
| Elasticsearch | BOTH direct (transaction search index on port 9202) AND indirect (audit-write writes audit events) |
| OpenAI        | fraud-service calls OpenAI API to score each transaction for fraud                                 |
| Redis         | NOT involved in any part of the transaction flow                                                   |
| S3            | NOT involved in the transaction flow (S3 is only for KYC documents)                               |
| SQS           | NOT involved in the transaction flow                                                               |

---

## Endpoints

### Public (require JWT via gateway — port 8080)

| Method | Path                                | Body / Params                        | Returns        |
|--------|-------------------------------------|--------------------------------------|----------------|
| POST   | /api/v1/transactions/transfer       | InitiateTransactionRequest (JSON)    | 202 TransactionResponse |
| POST   | /api/v1/transactions/payment        | InitiateTransactionRequest (JSON)    | 202 TransactionResponse |
| GET    | /api/v1/transactions                | ?page=0&size=20 (Pageable)           | Page<TransactionResponse> |
| GET    | /api/v1/transactions/{id}           | —                                    | TransactionResponse |
| GET    | /api/v1/transactions/search         | ?query=string                        | List<TransactionResponse> |

### Request body — InitiateTransactionRequest

```json
{
  "idempotencyKey": "unique-string-8-to-64-chars",
  "sourceAccountId": "uuid",
  "targetAccountId": "uuid",
  "targetAccountNumber": "string (optional)",
  "targetUserId": "uuid (optional)",
  "amount": 100.00,
  "currency": "MXN",
  "transactionType": "INTERNAL_TRANSFER | EXTERNAL_TRANSFER | PAYMENT | DIRECT_DEPOSIT | CASH_IN | CASH_OUT | FEE | INTEREST | REVERSAL",
  "channel": "API | MOBILE | WEB (optional)",
  "description": "optional string max 500",
  "merchantName": "optional string max 200",
  "merchantCategoryCode": "optional 4 chars",
  "referenceNumber": "optional string max 100"
}
```

**Fee note:** EXTERNAL_TRANSFER charges 1.5% fee automatically. TRANSFER and PAYMENT have zero fee.

### Internal (no JWT — service-to-service, gateway routes not defined in dev yet)

| Method | Path                                                   | Returns                                    |
|--------|--------------------------------------------------------|--------------------------------------------|
| GET    | /internal/v1/transactions/{id}/status                  | status, sagaId, sagaStep, isTerminal       |
| GET    | /internal/v1/accounts/{accountId}/transactions/active  | list of in-flight transactions             |
| POST   | /internal/v1/transactions/{id}/force-compensate        | forces FAILED on stuck transaction (admin) |
| GET    | /internal/v1/transactions/metrics                      | total, completed, failed, fraudRejected, activeInFlight |

---

## Transaction statuses (state machine)

```
INITIATED
  → FRAUD_CHECKING → FRAUD_CLEARED → BALANCE_RESERVING → BALANCE_RESERVED
                                                               → LEDGER_POSTING → LEDGER_POSTED → COMPLETING → COMPLETED ✓
                                                               → REVERSING → FAILED ✗
  → FRAUD_REJECTED → FAILED ✗
  → (any step) → FAILED ✗
```

---

## Known issues / things to verify before testing

| Issue | Detail |
|-------|--------|
| Elasticsearch port in config server | nexus-transaction-service-dev.yml has uris: http://localhost:9200 but ES host port is 9202. May need fix. |
| Internal gateway routes missing | /internal/v1/transactions/** has no route in application-dev.yml — hit directly on port 8086 for internal endpoints |
| Idempotency key required | every POST needs a unique idempotencyKey — sending the same key twice returns the original transaction (not a new one) |
| Returns 202 not 201 | transaction is async — INITIATED means it was accepted, not completed. Poll GET /{id} to track status to COMPLETED |
