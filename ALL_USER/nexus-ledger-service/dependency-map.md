# nexus-ledger-service — Complete Dependency Map

## Port: 8088

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                              |
|-------------------|-------|------------------------------------------------------------------|
| PostgreSQL        | 5433  | nexus_ledger DB — ledger_entries, accounts, outbox              |
| MongoDB           | 27019 | nexus_ledger — immutable ledger event store                     |
| Kafka             | 19092 | consumes saga.commands, produces saga.replies + ledger.posted   |
| Config service    | 8888  | loads config on startup                                         |
| Discovery service | 8761  | Eureka registration                                             |

**NO Redis, NO Elasticsearch, NO S3, NO SQS, NO OpenAI**

---

## Kafka topics CONSUMED

| Topic         | Group ID               | Sent by             | Commands handled  |
|---------------|------------------------|---------------------|-------------------|
| saga.commands | ledger-service-commands | saga-orchestrator  | PostLedgerCommand |

---

## Kafka topics PRODUCED

| Topic        | Consumed by                              | When                              |
|--------------|------------------------------------------|-----------------------------------|
| saga.replies | transaction-service (LedgerPostedReply)  | after double-entry posting        |
| ledger.posted | audit-write-native, analytics-service   | every successful ledger posting   |
| ledger.reversed | audit-write-native                    | when a posting is reversed        |

---

## Services that call ledger (it is the dependency)

| Caller             | How            | Why                                            |
|--------------------|----------------|------------------------------------------------|
| saga-orchestrator  | Kafka          | sends PostLedgerCommand after balance reserved |
| transaction-service | Kafka saga.replies | receives LedgerPostedReply                |
| admin / compliance | HTTP internal  | manual postings, reconciliation, integrity     |

---

## Public endpoints (require JWT via gateway)

| Method | Path                                              | Returns                          |
|--------|---------------------------------------------------|----------------------------------|
| GET    | /api/v1/ledger/accounts/{accountId}/balance       | double-entry balance             |
| GET    | /api/v1/ledger/accounts/{accountId}/entries       | paginated ledger entries         |
| GET    | /api/v1/ledger/accounts/{accountId}/summary/monthly | monthly debit/credit summary   |
| GET    | /api/v1/ledger/transactions/{transactionId}/posting | posting for a transaction      |
| POST   | /api/v1/ledger/...                                | (reconciliation endpoint)        |

---

## Internal endpoints (service-to-service, no JWT)

| Method | Path                                                  | Who calls it              |
|--------|-------------------------------------------------------|---------------------------|
| GET    | /internal/v1/ledger/accounts/{id}/balance             | account-service, analytics|
| POST   | /internal/v1/ledger/postings/manual                   | admin / compliance        |
| POST   | /internal/v1/ledger/postings/{postingId}/reverse      | admin / saga compensation |
| GET    | /internal/v1/ledger/reconciliation/status             | monitoring                |
| POST   | /internal/v1/ledger/accounts/{id}/reconstruct         | disaster recovery         |
| GET    | /internal/v1/ledger/integrity/verify                  | compliance audit          |

---

## Indirect dependencies

| Component     | Role                                                                   |
|---------------|------------------------------------------------------------------------|
| Redis         | NOT used directly                                                      |
| Elasticsearch | NOT used directly — audit-write indexes ledger events                  |
| S3 / SQS      | NOT used                                                               |
| OpenAI        | NOT used                                                               |

---

## Notes

- Double-entry accounting: every PostLedgerCommand creates DEBIT + CREDIT entries atomically
- MongoDB stores immutable event log; PostgreSQL stores mutable current state
- Gateway has no route for /internal/v1/ledger/** in dev — hit port 8088 directly
