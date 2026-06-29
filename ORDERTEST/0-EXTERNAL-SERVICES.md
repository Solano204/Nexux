# External Services Required — Full Platform

## Infrastructure — Local Dev (kafka/docker-compose.yml)

| Service | Host Port | Required By |
|---|---|---|
| PostgreSQL | 5433 | identity, account, transaction, fraud, ledger, kyc, risk, saga |
| MongoDB | 27018 | account, notification, kyc, audit-query |
| Redis | 6380 | identity, account, fraud, notification, analytics, risk |
| Kafka | 19092 | all services |
| Elasticsearch | 9201 | transaction, audit-write-native, audit-query |
| Zipkin | 9412 | all services (tracing — now connected after dev config fix) |
| Kafka Connect (Debezium) | 8083 | all services (outbox → Kafka CDC) |

## Infrastructure — Production (docker-compose-prod.yml)

| Service | Host Port | Required By |
|---|---|---|
| PostgreSQL | 5434 | identity, account, transaction, fraud, ledger, kyc, risk, saga |
| MongoDB | 27019 | account, notification, kyc, audit-query |
| Redis | 6381 | identity, account, fraud, notification, analytics, risk |
| Kafka | 19093 | all services |
| Elasticsearch | 9202 | transaction, audit-write-native, audit-query |
| Zipkin | 9413 | all services (optional, tracing) |
| Kafka Connect (Debezium) | 8083 | all services (outbox → Kafka CDC) |

## Debezium Connectors (auto-registered on compose up)

| Connector | Database | Routes by | Kafka topics produced |
|---|---|---|---|
| nexus-identity-outbox | nexus_identity | aggregate_type | users.registered, identity.verified, identity.rejected, identity.events |
| nexus-saga-outbox | nexus_saga | topic column | saga.commands, transactions.saga.completed |
| nexus-accounts-outbox | nexus_accounts | aggregate_type | accounts.created, account.events, account.frozen |
| nexus-transactions-outbox | nexus_transactions | aggregate_type | transactions.initiated, transactions.completed, transactions.failed |
| nexus-ledger-outbox | nexus_ledger | aggregate_type | ledger.posted, ledger.reversed |
| nexus-fraud-outbox | nexus_fraud | aggregate_type | fraud.flagged |

All saga replies (`saga.replies`) are published via the **nexus-saga-outbox** connector using the `topic` column.

## AWS Services (real AWS — Option B you chose)

| AWS Service | Used By | What For |
|---|---|---|
| S3 (bucket: nexus-kyc-documents) | nexus-identity-service | KYC document upload |
| SQS (queue: nexus-kyc-documents-pending) | nexus-identity-service | KYC job queue |
| Rekognition | nexus-ai-kyc-service | Face/document AI verification |

### .env values needed for AWS:
```
AWS_REGION=us-east-1
AWS_ENDPOINT=
AWS_ACCESS_KEY_ID=your_key
AWS_SECRET_ACCESS_KEY=your_secret
KYC_S3_BUCKET=nexus-kyc-documents
KYC_QUEUE_URL=https://sqs.us-east-1.amazonaws.com/{account-id}/nexus-kyc-documents-pending
```

## OpenAI API

| Used By | Feature |
|---|---|
| nexus-fraud-service | AI fraud analysis (tool calling agent) |
| nexus-ai-assistant-service | Chat + document analysis |
| nexus-analytics-service | AI financial insights generation |
| nexus-risk-scoring-service | AI risk profile computation |
| nexus-audit-query-jvm | Natural language compliance queries |
| nexus-ledger-service | Ledger explainer (SSE streaming) |
| nexus-account-service | Account financial advisor (SSE streaming) |

### .env value:
```
OPENAI_API_KEY=sk-...   ← currently placeholder, replace before testing AI endpoints
```

## Endpoints that SKIP if OpenAI key is missing (will return 500 or timeout)

- POST /api/v1/ai/chat
- POST /api/v1/ai/chat/analyze-document
- POST /api/v1/ai/documents/analyze
- GET /api/v1/analytics/accounts/{id}/insights/{yearMonth}
- POST /api/v1/ledger/accounts/{id}/explain
- POST /api/v1/accounts/{id}/advisor/chat
- GET /api/v1/accounts/{id}/advisor/insights
- POST /internal/v1/fraud/analyze (uses OpenAI agent)
- POST /internal/v1/risk/profiles/{userId}/compute
- POST /api/v1/audit/compliance/query

## Ollama (optional — ai-assistant fallback)

nexus-ai-assistant-service can use Ollama instead of OpenAI.
If Ollama is not running, service falls back to OpenAI only.
Not required unless you want to test local model fallback.

## Recap by test phase

| Phase | External needed |
|---|---|
| Auth / Identity (no KYC) | Nothing — just infra |
| KYC initiate | AWS S3 + SQS |
| KYC AI verify | AWS Rekognition |
| Transactions | Nothing extra |
| Ledger | Nothing extra |
| AI endpoints | OpenAI API key |
| Fraud analysis | OpenAI API key |
| Risk scoring | OpenAI API key |
| Compliance query | OpenAI API key |
