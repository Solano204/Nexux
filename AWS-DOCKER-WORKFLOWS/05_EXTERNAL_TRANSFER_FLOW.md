# EXTERNAL_TRANSFER Flow — Docker + AWS

## What it is
Transfer from a Nexus account to an external bank account (SPEI / interbank).
Fee: **1.5%** of amount — only transaction type with a fee.

## Entry Point
```
App → POST /api/v1/transactions → localhost:8080
Body: {
  transactionType: "EXTERNAL_TRANSFER",
  sourceAccountId: <uuid>,
  targetAccountNumber: "012345678901234567",   ← CLABE or account number
  amount: 1000.00,
  currency: "MXN",
  description: "Rent payment",
  idempotencyKey: "<unique-key>"
}
```

Fee calculated automatically: 1000.00 × 1.5% = 15.00 MXN added as feeAmount.

---

## Key difference vs INTERNAL_TRANSFER

| Field | INTERNAL_TRANSFER | EXTERNAL_TRANSFER |
|---|---|---|
| Target | `targetAccountId` (UUID) | `targetAccountNumber` (CLABE/account) |
| Target must exist in Nexus | Yes | No — external bank |
| Fee | 0% | 1.5% |
| Ledger credit | Nexus target account | Correspondent bank account |
| Delivery time | Instant | Same day / next day (SPEI) |

---

## Full Flow

```
App
 │
 │ POST /api/v1/transactions (EXTERNAL_TRANSFER)
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
│  2. Calculates fee: amount × 1.5%      │
│  3. Creates Transaction → INITIATED    │
│     feeAmount = 15.00 MXN             │
│  4. Writes Outbox → transactions.initiated
│  5. Returns 202 Accepted  ◄────────────┼── App gets response HERE
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
│  amount = principal + fee (1015.00)    │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  Reserves: principal + fee amount      │
│  Both deducted from available balance  │
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
│  transactionType: "EXTERNAL_TRANSFER"  │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-fraud-service :8087             │
│  External transfers = higher risk      │
│  AI scoring considers:                 │
│  - First external transfer             │
│  - Amount vs user history              │
│  - Beneficiary account pattern         │
│  - Time of day / device                │
│                                        │
│  IF high severity:                     │
└───────────┬────────────────────────────┘
            │              │ SQS: nexus-fraud-alerts-high-severity
            │              ▼
            │  ┌───────────────────────────┐
            │  │ nexus-fraud-alert-lambda  │
            │  │ SAR evaluation (CNBV)     │
            │  │ Compliance notification   │
            │  └───────────────────────────┘
            │
            │ Kafka: saga.commands (FraudCleared)
            ▼
═══════════════════════════════════════════
SAGA STEP 3 — Ledger Posting (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│  PostLedgerCommand                     │
│  postingType: "TRANSFER"               │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-ledger-service :8088            │
│  Double-entry posting:                 │
│    DEBIT  sourceAccount  - 1015.00     │
│    CREDIT correspondent  + 1000.00     │
│    CREDIT fee account    +   15.00     │
│  Reply → LedgerPosted                  │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 4 — Balance Finalization (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-saga-orchestrator :8095         │
│  FinalizeTransferCommand               │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  source: confirmed - 1015.00           │
│  Releases soft lock                    │
│  Reply → BalanceFinalized              │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 5 — Notification (timeout: 5min)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  "Transfer sent - pending bank delivery"
│  Publishes → SNS: nexus-notification-dispatch
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  EMAIL → SES: "Transfer submitted"     │
│  SMS   → SNS: "Transfer of $1,000 sent"│
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 6 — Completed ✓
═══════════════════════════════════════════
```

---

## What happens with the payment-processor-lambda?

The payment-processor-lambda handles **incoming** external payments (Visa/MC/external network → Nexus).
It does NOT handle outgoing EXTERNAL_TRANSFER. That is fully managed by the saga + ledger service.

---

## Kafka topics

| Topic | Role |
|---|---|
| `transactions.initiated` | Starts saga |
| `saga.commands` | Orchestrator → account, fraud, ledger, notification |
| `transactions.completed` | Analytics + risk scoring |
| `transactions.saga.completed` | Audit trail |
| `user.behavior.aggregated` | Risk profile update |

---

## Compensation (if failure)

Same as INTERNAL_TRANSFER:
- Release reserved balance (principal + fee) back to source account
- Send failure notification to user
