# INTEREST Flow — Docker + AWS

## What it is
Interest credit to a user account. System-generated — NOT initiated by the user.
Fee: **0%**. Credit-only — funds added from platform interest pool.

## Examples of when INTEREST is generated
- Monthly savings account interest
- Daily interest accrual on investment accounts
- Promotional interest rates
- Penalty interest (charged to borrower account)

## Entry Point
```
Interest Calculation Service → POST /api/v1/transactions → localhost:8080
Body: {
  transactionType: "INTEREST",
  sourceAccountId: <platform-interest-pool-uuid>,
  targetAccountId: <customer-account-uuid>,
  amount: 42.50,
  currency: "MXN",
  description: "Interés mensual - Cuenta de ahorro",
  referenceNumber: "INT-2026-06-01-ACC-0099",
  channel: "BATCH",
  idempotencyKey: "<period>-<accountId>-INTEREST"
}
```

---

## Key characteristics

| Attribute | Value |
|---|---|
| Initiated by | Interest calculation service (scheduled) |
| Channel | `BATCH` |
| Source | Platform interest pool account |
| Target | Customer account (credit) |
| Fraud check | Minimal — system-generated credit |
| Frequency | Monthly / daily depending on product |
| User notification | "Interest of $42.50 credited" |

---

## Full Flow

```
Interest Calculation Service (scheduled job)
 │
 │ POST /api/v1/transactions (INTEREST)
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
│  1. Idempotency (period + accountId)   │
│     Prevents double crediting          │
│  2. Creates Transaction → INITIATED    │
│     channel: BATCH                     │
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
│  accountId = interest pool account     │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  Reserves 42.50 from interest pool     │
│  Reply → BalanceReserved               │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 2 — Fraud Check (timeout: 60s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-fraud-service :8087             │
│  transactionType: "INTEREST"           │
│  channel: BATCH                        │
│  Virtually no fraud risk               │
│  → FraudCleared immediately            │
└──────────────────┬─────────────────────┘
                   │ FraudCleared
                   ▼
═══════════════════════════════════════════
SAGA STEP 3 — Ledger Posting (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-ledger-service :8088            │
│    DEBIT  interestPool      - 42.50    │
│    CREDIT customerAccount   + 42.50    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 4 — Balance Finalization (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  interestPool: confirmed - 42.50       │
│  customer:     confirmed + 42.50       │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 5 — Notification (timeout: 5min)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  "Interest of $42.50 credited"         │
│  Publishes → SNS                       │
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  PUSH  → "¡Ganaste $42.50 de interés!" │
│  EMAIL → monthly interest statement    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 6 — Completed ✓
═══════════════════════════════════════════
```

---

## Idempotency is critical here

The idempotencyKey pattern `<period>-<accountId>-INTEREST` ensures that even if the batch job runs twice in the same period, the interest is credited only once. The `transactionRepository.findByUserIdAndIdempotencyKey()` check in `TransactionCommandService` handles this.
