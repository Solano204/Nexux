# nexus-saga-orchestrator — Complete Dependency Map

## Port: 8095

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                                  |
|-------------------|-------|----------------------------------------------------------------------|
| PostgreSQL        | 5433  | nexus_saga DB — onboarding_sagas, transfer_sagas, saga_events tables |
| Kafka             | 19092 | consumes events, produces saga.commands to all downstream services   |
| Config service    | 8888  | loads config on startup                                              |
| Discovery service | 8761  | Eureka registration                                                  |

**NO Redis, NO MongoDB, NO Elasticsearch, NO S3, NO SQS, NO OpenAI**

---

## Kafka topics CONSUMED

| Topic                          | Consumer class          | What it starts/advances                         |
|--------------------------------|-------------------------|-------------------------------------------------|
| users.registered               | IdentityEventConsumer   | starts onboarding saga                          |
| identity.verified              | IdentityEventConsumer   | advances onboarding to ACCOUNTS_CREATING        |
| identity.rejected              | IdentityEventConsumer   | fails onboarding saga                           |
| saga.replies                   | SagaReplyConsumer       | routes all replies to correct saga processor    |
| transactions.initiated         | TransactionEventConsumer | starts transfer saga                           |

---

## Kafka topics PRODUCED (via saga.commands)

| Topic         | Consumed by           | Commands sent                                                            |
|---------------|-----------------------|--------------------------------------------------------------------------|
| saga.commands | identity-service      | InitiateKycVerificationCommand                                           |
| saga.commands | account-service       | CreateDefaultAccountsCommand, ReserveBalanceCommand, ReleaseBalanceCommand |
| saga.commands | fraud-service         | FraudCheckCommand                                                        |
| saga.commands | ledger-service        | PostLedgerCommand                                                        |
| saga.commands | notification-service  | SendWelcomeNotificationCommand                                           |

---

## Services that call saga (it is the dependency)

| Caller             | Endpoint                                          | Why                          |
|--------------------|---------------------------------------------------|------------------------------|
| admin / monitoring | GET /internal/v1/sagas/transfer/{transactionId}   | check transfer saga state    |
| admin / monitoring | GET /internal/v1/sagas/onboarding/{userId}        | check onboarding saga state  |
| admin / monitoring | GET /internal/v1/sagas/stuck                      | find stuck sagas             |

---

## All endpoints are internal

| Method | Path                                              | Returns                                     |
|--------|---------------------------------------------------|---------------------------------------------|
| GET    | /internal/v1/sagas/transfer/{transactionId}       | transfer saga state + current step          |
| GET    | /internal/v1/sagas/onboarding/{userId}            | onboarding saga state + current step        |
| GET    | /internal/v1/sagas/transfer/{transactionId}/history | full saga event history                   |
| GET    | /internal/v1/sagas/stats                          | total / completed / failed / stuck counts   |
| GET    | /internal/v1/sagas/stuck                          | list of sagas stuck > threshold time        |

---

## Saga flows

### Onboarding saga
```
users.registered → KYC_INITIATED
  → identity.verified → ACCOUNTS_CREATING
    → CreateDefaultAccountsCommand → ACCOUNTS_CREATED
      → SendWelcomeNotificationCommand → COMPLETED
  → identity.rejected → FAILED
```

### Transfer saga
```
transactions.initiated → FRAUD_CHECKING
  → FraudCheckCommand → fraud.result CLEARED → BALANCE_RESERVING
    → ReserveBalanceCommand → BalanceReservedReply → BALANCE_RESERVED
      → PostLedgerCommand → LedgerPostedReply → COMPLETING → COMPLETED
    → BalanceReservationFailedReply → COMPENSATING → FAILED
  → fraud.result REJECTED → FAILED
```

---

## Indirect dependencies

| Component | Role                                       |
|-----------|--------------------------------------------|
| Redis     | NOT used                                   |
| MongoDB   | audit-write writes saga events             |
| Elasticsearch | NOT used directly                      |
| S3 / SQS  | NOT used                                   |
| OpenAI    | NOT used                                   |

---

## Notes

- Gateway has no route for /internal/v1/sagas/** — hit port 8095 directly for admin queries
- Saga state is stored in PostgreSQL, not in-memory — survives restarts
- The NotificationSentReply routing bug (fixed): SagaReplyConsumer routes by originalCommand field
