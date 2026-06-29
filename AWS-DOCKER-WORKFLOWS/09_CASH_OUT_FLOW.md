# CASH_OUT Flow — Docker + AWS

## What it is
Cash withdrawal from a Nexus account at a branch or ATM.
Fee: **0%**. Debit-only — funds leave the account to cash vault.

## Entry Point
```
ATM Terminal / Branch Teller → POST /api/v1/transactions → localhost:8080
Body: {
  transactionType: "CASH_OUT",
  sourceAccountId: <customer-account-uuid>,
  targetAccountId: <cash-vault-account-uuid>,  ← ATM or branch vault
  amount: 500.00,
  currency: "MXN",
  description: "Retiro en cajero - ATM-Centro-001",
  referenceNumber: "ATM-001-20260629-1122",
  channel: "ATPM",     ← or "BRANCH"
  idempotencyKey: "<terminal-tx-id>"
}
```

---

## Key characteristics

| Attribute | Value |
|---|---|
| Initiated by | ATM terminal or branch teller |
| Channel | `ATPM` or `BRANCH` |
| Source | Customer account (debit) |
| Target | Cash vault account |
| Fraud risk | MEDIUM — stolen card / PIN risk |
| Daily limit | Typically enforced at account level |

---

## Full Flow

```
ATM Terminal / Branch Teller
 │
 │ POST /api/v1/transactions (CASH_OUT)
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
│     channel: ATPM / BRANCH             │
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
│  accountId = customer source account   │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  Checks: balance ≥ 500.00              │
│  Checks: daily withdrawal limit        │
│  Soft-locks 500.00                     │
│  Reply → BalanceReserved               │
│       OR BalanceReservationFailed      │
│          (insufficient / limit reached)│
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 2 — Fraud Check (timeout: 60s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-fraud-service :8087             │
│  transactionType: "CASH_OUT"           │
│  Fraud signals checked:                │
│  - Unusual withdrawal time (3am)       │
│  - Amount vs daily pattern             │
│  - New ATM location / city             │
│  - Multiple withdrawals today          │
│  - Card not present in last 30 days    │
│                                        │
│  IF flagged: FraudReview (4h pause)    │
│  OR: FraudRejected → compensation      │
└──────────────────┬─────────────────────┘
                   │ FraudCleared
                   ▼
═══════════════════════════════════════════
SAGA STEP 3 — Ledger Posting (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-ledger-service :8088            │
│    DEBIT  customerAccount  - 500.00    │
│    CREDIT cashVaultAccount + 500.00    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 4 — Balance Finalization (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  customer:   confirmed - 500.00        │
│  cashVault:  confirmed + 500.00        │
│  Releases soft lock                    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 5 — Notification (timeout: 5min)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  "Withdrawal of $500 processed"        │
│  "New balance: $X,XXX"                 │
│  Publishes → SNS                       │
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  PUSH → "Retiro de $500 realizado"     │
│  SMS  → "Retiro $500. Saldo: $X,XXX"  │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 6 — Completed ✓
Terminal dispenses cash to user
═══════════════════════════════════════════
```

---

## Compensation (fraud rejected or insufficient funds)

```
FRAUD_REJECTED or BALANCE_RESERVATION_FAILED
        │
        ▼
nexus-saga-orchestrator → startCompensation()
  ReleaseBalanceCommand → nexus-account-service
  Funds unfrozen immediately
  Failure notification → user:
  "No pudimos procesar tu retiro"
        │
        ▼
ATM Terminal: displays "Transaction declined"
Cash NOT dispensed
```

---

## Important: ATM must wait for saga completion

Unlike app flows where 202 is enough, an ATM terminal needs a definitive answer before dispensing cash. The ATM polls `GET /api/v1/transactions/{id}` until status = `COMPLETED` or `FAILED`.
