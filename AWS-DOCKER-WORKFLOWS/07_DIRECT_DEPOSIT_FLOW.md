# DIRECT_DEPOSIT Flow — Docker + AWS

## What it is
Income or salary deposited into a Nexus account by an employer or external party.
Fee: **0%**. Credit-only — funds arrive INTO the account.

## Entry Point
```
App / System → POST /api/v1/transactions → localhost:8080
Body: {
  transactionType: "DIRECT_DEPOSIT",
  sourceAccountId: <employer-pool-account-uuid>,  ← internal pool or external source
  targetAccountId: <employee-account-uuid>,
  amount: 15000.00,
  currency: "MXN",
  description: "Salario quincenal - Empresa SA de CV",
  referenceNumber: "NOM-2026-06-15-001",
  channel: "BATCH",
  idempotencyKey: "<payroll-run-id>"
}
```

---

## Key difference vs other transfers

| Field | INTERNAL_TRANSFER | DIRECT_DEPOSIT |
|---|---|---|
| Initiator | User (app) | Employer / payroll system |
| Channel | MOBILE / WEB | BATCH |
| Purpose | User moves money | Income arrives |
| Fraud profile | P2P patterns | Payroll patterns (low fraud risk) |
| Notification | "You sent $X" | "You received your salary $X" |

---

## Full Flow

```
Payroll System / Employer
 │
 │ POST /api/v1/transactions (DIRECT_DEPOSIT)
 │ channel: "BATCH"
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-transaction-service :8086       │
│                                        │
│  1. Idempotency check (referenceNumber)│
│  2. Creates Transaction → INITIATED    │
│     channel: BATCH                     │
│     description: "Salario quincenal"   │
│  3. Writes Outbox → transactions.initiated
│  4. Returns 202 Accepted               │
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
│  accountId = sourceAccountId           │
│  (employer pool account)               │
└──────────────────┬─────────────────────┘
                   │ Kafka: saga.commands
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  Checks pool account has funds         │
│  Reserves 15,000.00 from pool          │
│  Reply → BalanceReserved               │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 2 — Fraud Check (timeout: 60s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-fraud-service :8087             │
│  transactionType: "DIRECT_DEPOSIT"     │
│  Low-risk profile:                     │
│  - Regular payroll amounts             │
│  - Known employer source account       │
│  - BATCH channel = automated           │
│  Typically → FraudCleared quickly      │
└──────────────────┬─────────────────────┘
                   │ FraudCleared
                   ▼
═══════════════════════════════════════════
SAGA STEP 3 — Ledger Posting (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-ledger-service :8088            │
│  Double-entry posting:                 │
│    DEBIT  employerPool   - 15,000.00   │
│    CREDIT employeeAccount + 15,000.00  │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 4 — Balance Finalization (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  employerPool:    confirmed - 15,000   │
│  employeeAccount: confirmed + 15,000   │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 5 — Notification (timeout: 5min)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  Notification to recipient:            │
│  "Tu depósito de $15,000 ha llegado"   │
│  Publishes → SNS                       │
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  PUSH → "¡Tu quincena llegó! $15,000"  │
│  EMAIL → deposit confirmation          │
│  SMS   → "Depósito recibido $15,000"   │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 6 — Completed ✓
═══════════════════════════════════════════
```

---

## What AWS adds

| Step | Docker only | + AWS |
|---|---|---|
| Deposit processed | Works | Works |
| User notified | SNS published, not delivered | Real push/SMS/email: "Tu quincena llegó" |
