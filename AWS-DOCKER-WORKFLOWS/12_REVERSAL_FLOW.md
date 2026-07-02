# REVERSAL Flow — Docker + AWS

## What it is
Reversal of a previous transaction. Undoes the financial effect of a completed transaction.
Fee: **0%**. Can be debit or credit depending on what is being reversed.

## When REVERSAL is triggered
- Customer disputes a charge
- Merchant issues a refund
- Error correction by operations team
- Chargeback from external network
- Failed EXTERNAL_TRANSFER that was partially posted

## Entry Point
```
Operations / Dispute System → POST /api/v1/transactions → localhost:8080
Body: {
  transactionType: "REVERSAL",
  sourceAccountId: <account-that-received-original-funds>,
  targetAccountId: <account-that-originally-sent-funds>,
  amount: 350.00,
  currency: "MXN",
  description: "Reversal - Payment dispute TXN-abc123",
  referenceNumber: "TXN-abc123",   ← original transaction ID
  channel: "API",
  idempotencyKey: "REVERSAL-TXN-abc123"
}
```

---

## Key characteristics

| Attribute | Value |
|---|---|
| Initiated by | Operations team or dispute system |
| Channel | `API` or `BATCH` |
| Source | Account that currently holds the funds |
| Target | Account that originally sent the funds |
| Amount | Same as original transaction |
| Fraud check | Low risk — authorized reversal |
| Original txn | Marked as `REVERSED` status |

---

## Full Flow

```
Operations / Dispute System
 │
 │ POST /api/v1/transactions (REVERSAL)
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-transaction-service :8086       │
│                                        │
│  1. Idempotency ("REVERSAL-TXN-xxx")   │
│  2. Creates Transaction → INITIATED    │
│     type: REVERSAL                     │
│     referenceNumber = original txn ID  │
│  3. Writes Outbox → transactions.initiated
│  4. Returns 202 Accepted               │
└──────────────────┬─────────────────────┘
                   │ Kafka: transactions.initiated
                   ▼
═══════════════════════════════════════════
SAGA STEP 1 — Balance Reservation (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│  ReserveBalanceCommand                 │
│  accountId = source (currently holds   │
│             the funds to be reversed)  │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  Reserves reversal amount from source  │
│  Reply → BalanceReserved               │
│       OR BalanceReservationFailed      │
│          (funds may have been spent)   │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 2 — Fraud Check (timeout: 60s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-fraud-service :8087             │
│  transactionType: "REVERSAL"           │
│  Low risk profile:                     │
│  - Authorized reversal                 │
│  - Matches original transaction        │
│  → FraudCleared quickly                │
└──────────────────┬─────────────────────┘
                   │ FraudCleared
                   ▼
═══════════════════════════════════════════
SAGA STEP 3 — Ledger Posting (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-ledger-service :8088            │
│  Mirror of original entries:           │
│                                        │
│  Original was PAYMENT (user → merchant)│
│    DEBIT  merchantAccount  - 350.00    │
│    CREDIT userAccount      + 350.00    │
│                                        │
│  Creates reversal ledger entries       │
│  Linked to original by referenceNumber │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 4 — Balance Finalization (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  merchant:     confirmed - 350.00      │
│  user:         confirmed + 350.00      │
│                                        │
│  Also marks original transaction       │
│  as REVERSED in PostgreSQL             │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 5 — Notification (timeout: 5min)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  To user:  "Refund of $350 received"   │
│  Publishes → SNS                       │
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  PUSH  → "Reembolso de $350 acreditado"│
│  EMAIL → reversal confirmation         │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 6 — Completed ✓
Original transaction status → REVERSED
═══════════════════════════════════════════
```

---

## Edge case: source account has insufficient funds

If the merchant/recipient already spent the reversed funds:

```
BALANCE_RESERVATION_FAILED
        │
        ▼
Saga: completeWithFailure()
  No compensation needed (funds were never reserved)
  Notification to operations:
  "Reversal failed — insufficient funds in source account"
        │
        ▼
Operations must handle manually:
  - Payment plan
  - Legal dispute
  - Write-off
```

---

## Audit trail

```
REVERSAL completed
    │
    ├─► audit-write-native :8096
    │   Stores: reversalTxnId, originalTxnId,
    │           amount, accounts, timestamp
    │
    └─► nexus-analytics-service :8092
        Decrements category spend stats
        Kafka: transactions.completed (REVERSAL type)
```
