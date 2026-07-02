# FEE Flow — Docker + AWS

## What it is
Platform fee charged to a user account. System-generated — NOT initiated by the user.
Fee on the FEE transaction itself: **0%** (it IS the fee).

## Examples of when FEE is generated
- Monthly account maintenance fee
- EXTERNAL_TRANSFER fee (1.5%) — creates a separate FEE transaction
- Overdraft fee
- Card issuance fee
- Service fee for premium features

## Entry Point
```
Internal Service / Billing System → POST /api/v1/transactions → localhost:8080
Body: {
  transactionType: "FEE",
  sourceAccountId: <customer-account-uuid>,
  targetAccountId: <platform-revenue-account-uuid>,
  amount: 15.00,
  currency: "MXN",
  description: "Comisión transferencia SPEI",
  referenceNumber: "FEE-TXN-abc123",
  channel: "BATCH",
  idempotencyKey: "<original-txn-id>-FEE"
}
```

---

## Key characteristics

| Attribute | Value |
|---|---|
| Initiated by | Platform billing service (not user) |
| Channel | `BATCH` (automated) |
| Source | Customer account (debit) |
| Target | Platform revenue/income account |
| Fraud check | Low risk — system-generated |
| User notification | "Fee of $15 charged" |

---

## Full Flow

```
Platform Billing System
 │
 │ POST /api/v1/transactions (FEE)
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
│  1. Idempotency check                  │
│  2. Creates Transaction → INITIATED    │
│     channel: BATCH                     │
│     feeAmount: 0 (FEE is itself 0%)   │
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
│  Checks: balance ≥ fee amount          │
│  Soft-locks 15.00                      │
│  Reply → BalanceReserved               │
│       OR BalanceReservationFailed      │
│          (insufficient funds)          │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 2 — Fraud Check (timeout: 60s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-fraud-service :8087             │
│  transactionType: "FEE"                │
│  channel: BATCH = system-generated     │
│  Virtually always → FraudCleared       │
│  (system fees don't match fraud patterns)
└──────────────────┬─────────────────────┘
                   │ FraudCleared
                   ▼
═══════════════════════════════════════════
SAGA STEP 3 — Ledger Posting (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-ledger-service :8088            │
│    DEBIT  customerAccount   - 15.00    │
│    CREDIT revenueAccount    + 15.00    │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 4 — Balance Finalization (timeout: 30s)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-account-service :8085           │
│  customer: confirmed - 15.00           │
│  revenue:  confirmed + 15.00           │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 5 — Notification (timeout: 5min)
═══════════════════════════════════════════
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  "Fee of $15.00 charged to your account"
│  Publishes → SNS                       │
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  EMAIL → fee charge notification       │
│  PUSH  → "Comisión de $15 cobrada"     │
└──────────────────┬─────────────────────┘
                   │
                   ▼
═══════════════════════════════════════════
SAGA STEP 6 — Completed ✓
═══════════════════════════════════════════
```

---

## Relationship with EXTERNAL_TRANSFER

When a user does an EXTERNAL_TRANSFER of $1,000:
```
1. EXTERNAL_TRANSFER saga completes → $1,015 debited (principal + 1.5%)
2. Billing service detects completed transfer
3. Creates separate FEE transaction of $15
4. FEE saga runs → posts $15 to platform revenue account
5. User sees two entries: "Transferencia $1,000" + "Comisión $15"
```
