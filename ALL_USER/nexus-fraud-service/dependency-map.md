# nexus-fraud-service — Complete Dependency Map

## Port: 8087

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                                    |
|-------------------|-------|------------------------------------------------------------------------|
| PostgreSQL        | 5433  | nexus_fraud DB — fraud_decisions, blacklisted_merchants, fraud_policies |
| Redis             | 6380  | velocity cache, merchant blacklist cache                               |
| Kafka             | 19092 | consumes saga.commands, produces fraud.result + fraud.flagged          |
| OpenAI API        | HTTPS | FraudReActAgent uses GPT for AI fraud scoring + RAG policy tool        |
| Config service    | 8888  | loads config on startup                                                |
| Discovery service | 8761  | Eureka registration                                                    |

---

## Kafka topics CONSUMED

| Topic         | Group ID              | Sent by              | Commands handled       |
|---------------|-----------------------|----------------------|------------------------|
| saga.commands | fraud-service-commands | saga-orchestrator   | FraudCheckCommand      |

---

## Kafka topics PRODUCED

| Topic         | Consumed by                                          | When                              |
|---------------|------------------------------------------------------|-----------------------------------|
| fraud.result  | transaction-service                                  | after AI fraud analysis completes |
| saga.replies  | transaction-service (FraudRejectedReply)             | when fraud REJECTED               |
| fraud.flagged | notification-service, audit-write-native             | when high-risk transaction flagged|

---

## Services that call fraud (it is the dependency)

| Caller              | How           | Why                                        |
|---------------------|---------------|--------------------------------------------|
| saga-orchestrator   | Kafka         | sends FraudCheckCommand for every transaction |
| transaction-service | Kafka saga.replies | receives fraud decision                |
| admin / compliance  | HTTP internal | manual review, blacklist management        |

---

## Services fraud calls outbound

| Service           | How            | Why                                            |
|-------------------|----------------|------------------------------------------------|
| transaction-service | HTTP GET /internal/v1/transactions/{id}/status | get transaction context for fraud check |
| identity-service  | HTTP GET /internal/v1/users/{id}/identity      | get user profile for risk enrichment    |
| OpenAI            | HTTPS API      | FraudReActAgent — AI scoring + reasoning       |

---

## Internal endpoints (all endpoints are internal — no public user-facing API)

| Method | Path                                              | Who calls it             |
|--------|---------------------------------------------------|--------------------------|
| POST   | /internal/v1/fraud/analyze                        | saga-orchestrator / admin|
| GET    | /internal/v1/fraud/decisions/{transactionId}      | compliance / admin       |
| GET    | /internal/v1/fraud/decisions/user/{userId}        | compliance / admin       |
| GET    | /internal/v1/fraud/decisions/pending-reviews      | compliance team          |
| POST   | /internal/v1/fraud/merchants/blacklist/{merchantId} | admin                  |
| DELETE | /internal/v1/fraud/merchants/blacklist/{merchantId} | admin                  |
| POST   | /internal/v1/fraud/review/{decisionId}/outcome    | compliance team          |
| POST   | /internal/v1/fraud/review/{decisionId}/sar        | compliance (SAR filing)  |
| GET    | /internal/v1/fraud/metrics                        | monitoring / health      |
| GET    | /internal/v1/fraud/policies/search                | admin                    |

---

## Indirect dependencies

| Component     | Role                                                                      |
|---------------|---------------------------------------------------------------------------|
| MongoDB       | audit-write-native writes fraud events to nexus_audit                     |
| Elasticsearch | NOT used directly — audit-write indexes fraud events indirectly           |
| S3 / SQS      | NOT used                                                                  |

---

## Notes

- All endpoints are internal — exposed only within Docker network in prod (RemoteAddr predicate)
- Uses ReAct (Reasoning + Acting) agent pattern with OpenAI for explainable fraud decisions
- RagPolicyTool: retrieves fraud policies from DB to ground the AI decisions
- Gateway dev config has JwtAuthentication on /internal/v1/fraud/** — this is wrong for an internal service, but it works because the saga-orchestrator is not hitting fraud via gateway
- OpenAI API key must be real for fraud AI to work — placeholder key = fraud check always fails
