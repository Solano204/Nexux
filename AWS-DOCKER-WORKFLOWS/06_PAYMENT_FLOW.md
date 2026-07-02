# PAYMENT Flow — Docker + AWS

## What it is
Payment from a Nexus account to a merchant or business.
Fee: **0%**

## Entry Point
```
App → POST /api/v1/transactions → localhost:8080
Body: {
  transactionType: "PAYMENT",
  sourceAccountId: <uuid>,
  targetAccountId: <uuid>,        ← merchant's Nexus account
  amount: 350.00,
  currency: "MXN",
  merchantName: "Oxxo",
  merchantCategoryCode: "5411",   ← MCC: Grocery Stores
  idempotencyKey: "<unique-key>"
}
```

---

## Key difference vs INTERNAL_TRANSFER

| Field | INTERNAL_TRANSFER | PAYMENT |
|---|---|---|
| Purpose | Person-to-person | Person-to-merchant |
| merchantName | null | Required |
| merchantCategoryCode | null | Required (4-digit MCC) |
| Fraud profile | P2P patterns | Card-present / merchant patterns |
| Analytics category | by type | by MCC category |
| Risk scoring | User behavior | Merchant frequency tracking |

---

## Full Flow

```
App
 │
 │ POST /api/v1/transactions (PAYMENT)
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-transaction-service :8086       │
│                                        │
│  1. Idempotency check                  │
│  2. Creates Transaction → INITIATED    │
│     merchantName = "Oxxo"             │
│     merchantCategoryCode = "5411"      │
│  3. Writes Outbox → transactions.initiated
│  4. Returns 202 Accepted  ◄────────────┼── App gets response HERE
└──────────────────┬─────────────────────┘
                   │ Debezium CDC
                   │ Kafka: transactions.initiated
                   ▼
═══════════════════════════════════════════
SAGA STEP 1 — Balance Reservation (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│  ReserveBalanceCommand                 │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  Soft-locks 350.00 from source         │
│  Reply → BalanceReserved               │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 2 — Fraud Check (timeout: 60s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│  CheckFraudCommand                     │
│  transactionType: "PAYMENT"            │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-fraud-service :8087             │
│  Fraud signals for payments:           │
│  - Unusual MCC for user                │
│  - Amount vs user spend patterns       │
│  - Merchant frequency (first visit?)   │
│  - Device / location mismatch          │
│  - Velocity (many payments in short time)
│                                        │
│  IF high severity:                     │
└───────────┬────────────────────────────┘
            │              │ SQS (async)
            │              ▼
            │  ┌───────────────────────────┐
            │  │ nexus-fraud-alert-lambda  │
            │  │ CNBV SAR compliance       │
            │  └───────────────────────────┘
            │
            │ Kafka: FraudCleared
            ▼
═══════════════════════════════════════════
SAGA STEP 3 — Ledger Posting (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-ledger-service :8088            │
│  Double-entry posting:                 │
│    DEBIT  sourceAccount  - 350.00      │
│    CREDIT merchantAccount + 350.00     │
│  Reply → LedgerPosted                  │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 4 — Balance Finalization (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  source: confirmed - 350.00            │
│  merchant: confirmed + 350.00          │
│  Releases soft lock                    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 5 — Notification (timeout: 5min)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  Notification to payer:                │
│  "Payment of $350 to Oxxo processed"   │
│  Publishes → SNS: nexus-notification-dispatch
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  EMAIL + SMS + PUSH → payer            │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 6 — Completed ✓
═══════════════════════════════════════════
```

---

## Analytics side effect (after COMPLETED)

```
transactions.completed → nexus-analytics-service :8092
  Groups by MCC category (5411 = Grocery)
  Updates merchant frequency tables
  Feeds spending breakdown in user dashboards

user.behavior.aggregated → nexus-risk-scoring-service :8094
  Updates merchant visit frequency
  Adjusts user spend profile
```

---

## Kafka topics

| Topic | Role |
|---|---|
| `transactions.initiated` | Starts saga |
| `saga.commands` | Orchestrator → account, fraud, ledger, notification |
| `transactions.completed` | Analytics (MCC-based categorization) |
| `user.behavior.aggregated` | Merchant frequency risk scoring |
