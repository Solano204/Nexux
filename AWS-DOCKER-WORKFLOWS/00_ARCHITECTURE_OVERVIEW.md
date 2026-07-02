# NEXUS Platform — Architecture Overview (Docker + AWS)

## Dual-Plane Architecture

The platform runs two planes simultaneously. They are NOT a backup of each other — they extend each other.

```
INTERNET / EXTERNAL WORLD
        │
        ▼
┌───────────────────────────────────────────────────────┐
│                    AWS PLANE (Plane B)                │
│                                                       │
│  User uploads ID doc → S3 → kyc-rekognition-lambda   │
│  External payment arrives → SQS → payment-lambda     │
│  Auth request → API GW → auth-lambda → Cognito       │
│  Notification queued → SNS → notification-lambda     │
│  health-monitor-lambda (polls services every 5min)   │
└───────────────────────┬───────────────────────────────┘
                        │ HTTP / SQS / SNS / Kafka bridge
                        ▼
┌───────────────────────────────────────────────────────┐
│              DOCKER PLANE (Plane A — 18 services)    │
│                                                       │
│  nexus-api-gateway          :8080  ← only entry point│
│  nexus-config-service       :8888                    │
│  nexus-discovery-service    :8761                    │
│  nexus-identity-service     :8083                    │
│  nexus-account-service      :8085                    │
│  nexus-transaction-service  :8086                    │
│  nexus-fraud-service        :8087                    │
│  nexus-ledger-service       :8088                    │
│  nexus-notification-service :8089                    │
│  nexus-ai-assistant-service :8090                    │
│  nexus-ai-kyc-service       :8091                    │
│  nexus-analytics-service    :8092                    │
│  nexus-risk-scoring-service :8094                    │
│  nexus-saga-orchestrator    :8095                    │
│  audit-write-native         :8096  (Quarkus)         │
│  nexus-audit-query-jvm      :8097                    │
└───────────────────────────────────────────────────────┘
```

## Rule: The app ALWAYS calls localhost:8080

The frontend/mobile app never calls AWS directly.
AWS lambdas are event-driven workers, never HTTP entry points for the app.

```
App → localhost:8080 (API Gateway) → Docker services → AWS when needed
```

## The 8 Lambda Functions

| Lambda | Trigger | What it adds |
|---|---|---|
| nexus-auth-lambda | API GW HTTP | Cognito JWT + DynamoDB token revocation |
| nexus-payment-processor-lambda | SQS | External payment network ingestion |
| nexus-fraud-alert-lambda | SQS | CNBV SAR compliance + regulatory notifications |
| nexus-notification-dispatcher-lambda | SNS | Real email (SES) + SMS + push delivery |
| nexus-kyc-rekognition-lambda | S3 | AWS Rekognition face + text on ID documents |
| nexus-analytics-aggregator-lambda | DynamoDB Streams | Cloud-side analytics aggregation |
| nexus-reporting-lambda | CloudWatch cron | Daily compliance/operations reports to S3 |
| nexus-health-monitor-lambda | CloudWatch rate(5m) | External health monitoring + alerting |

## What works without AWS (Docker only)

- All core banking: accounts, transfers, payments, ledger, saga
- JWT auth (local keystore)
- Fraud detection (AI scoring)
- Notification queuing (messages published but not delivered)
- KYC initiation (document upload endpoint works, Rekognition result never comes)

## What requires AWS

- Real email / SMS / push delivery to users
- KYC document verification (Rekognition)
- External payment network ingestion (Visa/MC/etc)
- Token revocation that survives Redis restarts
- Regulatory compliance SAR records
- Daily automated reports
