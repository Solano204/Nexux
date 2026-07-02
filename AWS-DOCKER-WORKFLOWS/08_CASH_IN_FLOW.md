# CASH_IN Flow — Docker + AWS

## What it is
Physical cash deposited into a Nexus account at a branch or ATM.
Fee: **0%**. Credit-only — funds added to account from cash vault.

## Entry Point
```
Teller System / ATM → POST /api/v1/transactions → localhost:8080
Body: {
  transactionType: "CASH_IN",
  sourceAccountId: <cash-vault-account-uuid>,  ← branch/ATM internal account
  targetAccountId: <customer-account-uuid>,
  amount: 2000.00,
  currency: "MXN",
  description: "Depósito en efectivo - Sucursal Centro",
  referenceNumber: "BRANCH-001-20260629-0045",
  channel: "BRANCH",     ← or "ATPM" for ATM
  idempotencyKey: "<terminal-tx-id>"
}
```

---

## Key characteristics

| Attribute | Value |
|---|---|
| Initiated by | Branch teller or ATM terminal |
| Channel | `BRANCH` or `ATPM` |
| Source | Internal cash vault account (not user-owned) |
| Target | Customer account (credit) |
| Fraud risk | Low — physical presence required |
| Notification | "Cash deposit received" |

---

## Full Flow

```
Branch Teller / ATM Terminal
 │
 │ POST /api/v1/transactions (CASH_IN)
 │ channel: "BRANCH" or "ATPM"
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-transaction-service :8086       │
│                                        │
│  1. Idempotency (terminal reference)   │
│  2. Creates Transaction → INITIATED    │
│     channel: BRANCH / ATPM             │
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
│  accountId = cash vault account        │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  Reserves 2,000 from cash vault        │
│  Reply → BalanceReserved               │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 2 — Fraud Check (timeout: 60s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-fraud-service :8087             │
│  transactionType: "CASH_IN"            │
│  Low-risk profile:                     │
│  - Physical presence (branch/ATM)      │
│  - Channel is BRANCH or ATPM           │
│  Typically → FraudCleared quickly      │
│                                        │
│  Exception: large cash deposits        │
│  may trigger SAR (CNBV structuring)    │
└──────────────────┬─────────────────────┘
                   │ FraudCleared
                   ▼
═══════════════════════════════════════════
SAGA STEP 3 — Ledger Posting (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-ledger-service :8088            │
│    DEBIT  cashVaultAccount  - 2,000    │
│    CREDIT customerAccount   + 2,000    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 4 — Balance Finalization (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  cashVault:    confirmed - 2,000       │
│  customer:     confirmed + 2,000       │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 5 — Notification (timeout: 5min)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  "Cash deposit of $2,000 received"     │
│  Publishes → SNS                       │
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  PUSH → "Depósito de $2,000 acreditado"│
│  SMS  → deposit confirmation           │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 6 — Completed ✓
═══════════════════════════════════════════
```

---

## Large cash deposit — SAR trigger (AWS only)

```
Cash deposit ≥ threshold (e.g. $10,000 MXN single deposit
or structuring pattern detected)
        │
        ▼ (async, does NOT block transaction)
nexus-fraud-service → SQS: nexus-fraud-alerts-high-severity
        │
        ▼
nexus-fraud-alert-lambda (AWS)
  - Pattern: STRUCTURING detected
  - Creates SAR consideration in DynamoDB
  - CNBV 15-day regulatory deadline started
  - Notifies compliance team via SNS email
```

Transaction still completes. SAR is a compliance side-effect, not a blocker.
