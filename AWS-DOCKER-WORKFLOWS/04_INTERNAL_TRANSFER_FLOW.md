# INTERNAL_TRANSFER Flow — Docker + AWS

## Entry Point
```
App → POST /api/v1/transactions → localhost:8080
Body: {
  transactionType: "INTERNAL_TRANSFER",
  sourceAccountId: <uuid>,
  targetAccountId: <uuid>,
  amount: 500.00,
  currency: "MXN",
  idempotencyKey: "<unique-key>"
}
```

Fee: 0% (internal transfers have no fee)

---

## Full Flow

```
App
 │
 │ POST /api/v1/transactions
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
│  Routes → nexus-transaction-service    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-transaction-service :8086       │
│                                        │
│  1. Idempotency check (PostgreSQL)     │
│  2. Creates Transaction → INITIATED    │
│  3. Writes Outbox → transactions.initiated
│  4. Indexes to Elasticsearch (async)   │
│  5. Returns 202 Accepted  ◄────────────┼── App gets response HERE
│     { transactionId,                   │   rest is fully async
│       status: "INITIATED" }            │
└──────────────────┬─────────────────────┘
                   │ Debezium CDC
                   │ Kafka: transactions.initiated
                   ▼
═══════════════════════════════════════════
SAGA STEP 1 — Balance Reservation (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│                                        │
│  handleTransactionInitiated()          │
│  Creates TransferSagaState → STARTED   │
│  Publishes ReserveBalanceCommand       │
│  → Kafka: saga.commands                │
│  Step: BALANCE_RESERVING               │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│                                        │
│  Consumes ReserveBalanceCommand        │
│  Checks: balance ≥ amount              │
│  Soft-locks funds (reservationId)      │
│  Reply → BalanceReserved               │
│       OR BalanceReservationFailed      │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands (reply)
                   ▼
═══════════════════════════════════════════
SAGA STEP 2 — Fraud Check (timeout: 60s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│                                        │
│  handleBalanceReserved()               │
│  Stores reservationId                  │
│  Step: BALANCE_RESERVED                │
│  Publishes CheckFraudCommand           │
│  → Kafka: saga.commands                │
│  Step: FRAUD_CHECKING                  │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-fraud-service :8087             │
│                                        │
│  Consumes CheckFraudCommand            │
│  Runs AI fraud scoring (OpenAI)        │
│                                        │
│  score < 90  → FraudCleared           │
│  score ≥ 90  → FraudRejected          │
│  flagged     → FraudReview (pause 4h)  │
│                                        │
│  IF high severity detected (async):    │
└────────────┬───────────────────────────┘
             │                  │
             │ (saga reply)     │ SQS: nexus-fraud-alerts-high-severity
             │                  ▼
             │    ┌─────────────────────────────┐
             │    │ nexus-fraud-alert-lambda AWS │
             │    │ - Classify alert severity   │
             │    │ - Evaluate CNBV SAR criteria│
             │    │   (15-day regulatory clock) │
             │    │ - Store in DynamoDB (7yr)   │
             │    │ - Notify compliance via SNS │
             │    │ - Notify security ops       │
             │    └─────────────────────────────┘
             │
             │ Kafka: saga.commands (FraudCleared)
             ▼
═══════════════════════════════════════════
SAGA STEP 3 — Ledger Posting (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│                                        │
│  handleFraudCleared()                  │
│  Step: FRAUD_CLEARED                   │
│  Publishes PostLedgerCommand           │
│  → Kafka: saga.commands                │
│  Step: LEDGER_POSTING                  │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-ledger-service :8088            │
│                                        │
│  Consumes PostLedgerCommand            │
│  Creates double-entry records:         │
│    DEBIT  sourceAccount - amount       │
│    CREDIT targetAccount + amount       │
│  Stores debitEntryId + creditEntryId   │
│  Reply → LedgerPosted                  │
│       OR LedgerFailed                  │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands (reply)
                   ▼
═══════════════════════════════════════════
SAGA STEP 4 — Balance Finalization (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│                                        │
│  handleLedgerPosted()                  │
│  Stores debitEntryId + creditEntryId   │
│  Step: LEDGER_POSTED                   │
│  Publishes FinalizeTransferCommand     │
│  → Kafka: saga.commands                │
│  Step: BALANCE_FINALIZING              │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│                                        │
│  Consumes FinalizeTransferCommand      │
│  source: confirmed - amount            │
│  target: confirmed + amount            │
│  Releases soft lock (reservationId)    │
│  Reply → BalanceFinalized              │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands (reply)
                   ▼
═══════════════════════════════════════════
SAGA STEP 5 — Notifications (timeout: 5min)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│                                        │
│  handleBalanceFinalized()              │
│  Step: BALANCE_FINALIZED               │
│  Publishes SendTransactionNotificationCommand
│  → Kafka: saga.commands                │
│  Step: NOTIFICATION_SENDING            │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│                                        │
│  Builds DispatchRequest for:           │
│  - sender  (money sent notification)   │
│  - receiver (money received notification)
│  Publishes → SNS: nexus-notification-dispatch
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  (AWS)                                 │
│                                        │
│  EMAIL → SES v2   → user inbox         │
│  SMS   → SNS      → user phone         │
│  PUSH  → SNS      → APNs / FCM        │
│                                        │
│  Reports → SQS: nexus-delivery-status  │
└──────────────────┬─────────────────────┘
                   │ SQS consumed by
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  Updates delivery record in DB         │
│  Reply → NotificationSent              │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands (reply)
                   ▼
═══════════════════════════════════════════
SAGA STEP 6 — Completed
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│                                        │
│  handleNotificationSent()              │
│  Step: COMPLETED ✓                     │
│  Publishes → transactions.saga.completed
└────────────────────────────────────────┘
```

---

## Parallel flows throughout the saga

```
Every state change ──► audit-write-native :8096
                        Quarkus native — high-throughput audit trail

After COMPLETED ──────► nexus-analytics-service :8092
                         Consumes transactions.completed
                         Builds local analytics + dashboards

                  ──────► nexus-risk-scoring-service :8094
                           Consumes user.behavior.aggregated
                           Updates user risk profile
```

---

## Compensation flow (if any step fails)

```
ANY FAILURE after balance reserved
        │
        ▼
nexus-saga-orchestrator
  startCompensation()
  Publishes ReleaseBalanceCommand
  → Kafka: saga.commands
  Step: RELEASING_BALANCE (timeout: 30s)
        │
        ▼
nexus-account-service
  Releases soft lock
  Returns reserved funds to source account
  Reply → BalanceReleased
        │
        ▼
nexus-saga-orchestrator
  handleBalanceReleased()
  Step: COMPENSATION_COMPLETED
  Publishes SendTransactionFailureNotificationCommand
  → nexus-notification-service
  → SNS → notification-dispatcher-lambda
  → User receives failure notification with explanation
```

Compensation does NOT trigger if balance was never reserved (e.g. BALANCE_RESERVATION_FAILED).
Notification failure (step 5) does NOT trigger compensation — money has already moved.

---

## Kafka topics used

| Topic | Published by | Consumed by |
|---|---|---|
| `transactions.initiated` | nexus-transaction-service | nexus-saga-orchestrator |
| `saga.commands` | nexus-saga-orchestrator | nexus-account-service, nexus-fraud-service, nexus-ledger-service, nexus-notification-service |
| `transactions.completed` | nexus-transaction-service | nexus-analytics-service |
| `transactions.failed` | nexus-transaction-service | nexus-notification-service |
| `transactions.saga.completed` | nexus-saga-orchestrator | audit-write-native |
| `transactions.saga.failed` | nexus-saga-orchestrator | audit-write-native |
| `user.behavior.aggregated` | nexus-transaction-service | nexus-risk-scoring-service |

---

## Saga step timeouts

| Step | Timeout | On timeout |
|---|---|---|
| BALANCE_RESERVATION | 30s | Compensation |
| FRAUD_CHECK | 60s | Compensation |
| FRAUD_REVIEW (manual) | 4h | Compensation |
| LEDGER_POST | 30s | Compensation |
| BALANCE_FINALIZE | 30s | Compensation |
| NOTIFICATION | 5min | Saga completes anyway — money moved |

---

## What AWS adds vs Docker only

| Step | Docker only | + AWS |
|---|---|---|
| Transaction created | Works | Works |
| Balance reserved | Works | Works |
| Fraud check | Works (local AI) | + High-severity alerts → SAR compliance in DynamoDB |
| Ledger posted | Works | Works |
| Balance finalized | Works | Works |
| Notification | Published to SNS, nobody listens | Real email + SMS delivered to both users |
