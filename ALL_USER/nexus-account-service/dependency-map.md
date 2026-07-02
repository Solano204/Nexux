# nexus-account-service — Complete Dependency Map

## Port: 8085

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                              |
|-------------------|-------|------------------------------------------------------------------|
| PostgreSQL        | 5433  | nexus_accounts DB — accounts table, outbox, event store         |
| MongoDB           | 27019 | nexus_accounts — event sourcing documents                        |
| Redis             | 6380  | balance reservation locks, session cache                        |
| Kafka             | 19092 | consumes saga.commands, publishes saga.replies + accounts.created|
| Config service    | 8888  | loads all config on startup                                     |
| Discovery service | 8761  | Eureka registration                                             |

---

## Kafka topics CONSUMED

| Topic         | Group ID                    | Sent by             | Commands handled                                            |
|---------------|-----------------------------|---------------------|-------------------------------------------------------------|
| saga.commands | account-service-saga-commands | saga-orchestrator | ReserveBalanceCommand, ReleaseBalanceCommand, CreateDefaultAccountsCommand |

---

## Kafka topics PRODUCED

| Topic            | Consumed by                               | When                             |
|------------------|-------------------------------------------|----------------------------------|
| saga.replies     | transaction-service, saga-orchestrator    | BalanceReservedReply, BalanceReservationFailedReply |
| accounts.created | notification-service, audit-write-native  | after default accounts created on onboarding |

---

## Services that call account (it is the dependency)

| Caller               | Endpoint                                              | Why                                |
|----------------------|-------------------------------------------------------|------------------------------------|
| saga-orchestrator    | POST /internal/api/v1/accounts/create-defaults        | create checking + savings on onboarding complete |
| transaction-service  | via Kafka saga.commands only                          | reserve / release balance          |
| fraud-service        | GET /internal/v1/users/{id}/identity (identity svc)  | via identity — not direct          |

---

## Services account calls outbound

| Service           | How              | Why                                   |
|-------------------|------------------|---------------------------------------|
| identity-service  | HTTP GET /internal/v1/users/{userId}/identity | verify user exists before account ops |
| transaction-service | HTTP GET /internal/v1/accounts/{id}/transactions/active | check in-flight txns before close |
| saga-orchestrator | Kafka saga.replies | publish balance result back          |
| notification-service | Kafka accounts.created | trigger account creation notification |

---

## Public endpoints (require JWT via gateway)

| Method | Path                                        | Returns                        |
|--------|---------------------------------------------|--------------------------------|
| GET    | /api/v1/accounts                            | list of user's accounts        |
| GET    | /api/v1/accounts/{accountId}                | account detail                 |
| GET    | /api/v1/accounts/{accountId}/balance        | current balance                |
| GET    | /api/v1/accounts/{accountId}/events         | event history (event sourcing) |
| GET    | /api/v1/accounts/{accountId}/analytics      | spending analytics             |
| POST   | /api/v1/accounts/{accountId}/advisor/ask    | AI financial advice (body: question) |
| GET    | /api/v1/accounts/{accountId}/advisor/insights | pre-computed AI insights      |

---

## Internal endpoints (service-to-service, no JWT)

| Method | Path                                                  | Who calls it          |
|--------|-------------------------------------------------------|-----------------------|
| POST   | /internal/api/v1/accounts/{id}/reserve                | saga-orchestrator     |
| POST   | /internal/api/v1/accounts/{id}/release                | saga-orchestrator     |
| POST   | /internal/api/v1/accounts/finalize-transfer           | saga-orchestrator     |
| POST   | /internal/api/v1/accounts/create-defaults             | saga-orchestrator (onboarding) |
| POST   | /internal/api/v1/accounts/{id}/freeze                 | fraud-service / admin |
| POST   | /internal/api/v1/accounts/{id}/unfreeze               | admin                 |
| GET    | /internal/api/v1/accounts/{id}/balance-check          | pre-transaction check |
| GET    | /internal/api/v1/accounts/by-user/{userId}            | identity/fraud lookup |

---

## Indirect dependencies

| Component     | Role                                                                    |
|---------------|-------------------------------------------------------------------------|
| Elasticsearch | NOT used directly by account service                                    |
| S3 / SQS      | NOT used                                                                |
| OpenAI        | Used indirectly via AccountAdvisorController → calls AI assistant service |

---

## Notes

- Uses both PostgreSQL (relational state) AND MongoDB (event store) simultaneously
- Internal path prefix is `/internal/api/v1/accounts` (NOT `/internal/v1/accounts`) — different from other services
- Gateway dev config has no route for `/internal/api/v1/accounts/**` — hit port 8085 directly for internal endpoints
