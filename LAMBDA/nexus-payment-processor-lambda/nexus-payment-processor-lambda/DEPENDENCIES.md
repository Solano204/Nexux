# nexus-payment-processor-lambda — Complete Dependency & Run Guide

## No source code bugs found

Two things were **missing from the repo**:

| Item | Status |
|------|--------|
| `samconfig.toml` | **NEW** — was missing |
| `.github/workflows/nexus-payment-processor-lambda.yml` | **NEW** — includes bridge mode verification |

The repo already had `events/visa-payment-event.json` and `events/mastercard-payment-event.json` — both preserved.

---

## 1. What this Lambda does

**Trigger:** SQS queue `payment.incoming` (BatchSize 10, 5-second batching window)  
**Runtime:** Java 21, **no SnapStart** (by design — see below)

**Processing pipeline per message (7 steps):**
1. Deserialize `IncomingPaymentEvent` from SQS message body
2. Validate: card network schema, required fields, amount range
3. Idempotency: DynamoDB conditional write — skip duplicates silently
4. Enrich: resolve `nexusAccountId`, normalize currency to MXN
5. Bridge: publish to Plane A Kafka (HTTP or MSK — configurable)
6. Publish to SNS `payment.processed` for downstream fan-out
7. Mark idempotency record as PROCESSED (TTL: 24h)

**Why no SnapStart:** The handler comment explains — SQS batch processing is latency-tolerant. Cold starts happen during low-volume periods (when Lambda scales down), not during payment bursts (when it stays warm). The trade-off of skipping SnapStart is acceptable.

---

## 2. Kafka Bridge — HTTP vs MSK

The `KafkaBridgeMode` parameter switches between two bridge implementations:

| Mode | Class | When to use |
|------|-------|-------------|
| `HTTP` | `HttpKafkaBridgeClient` | Default. Calls Plane A API Gateway `/internal/v1/bridge/publish`. Works from any network. |
| `MSK` | `MskKafkaBridgeClient` | Lower latency. Publishes directly to Amazon MSK. Requires Lambda in VPC with MSK security group access. |

In development: use `HTTP` (no VPC required, works with local Docker).  
In production: switch to `MSK` for sub-10ms bridge latency by updating `samconfig.toml`.

---

## 3. Idempotency design

The `nexus-payment-idempotency` DynamoDB table prevents duplicate payment processing:

- Key: `PK = PAYMENT#{externalPaymentId}` (card network's unique ID)
- On first receipt: conditional write succeeds → process payment
- On duplicate (same `externalPaymentId` arrives again): conditional write fails → skip silently → report success to SQS (message deleted, not retried)
- TTL: 24 hours (payment networks don't retry after 24h)

This is the **payment layer** idempotency. The Kafka consumer in `nexus-transaction-service` has its own idempotency for the transaction state machine.

---

## 4. Partial batch failure

`FunctionResponseTypes: ReportBatchItemFailures` means:
- Each of the 10 messages is processed independently
- If message 3 fails (e.g., Kafka bridge timeout), only message 3 returns to queue
- Messages 1-2 and 4-10 are deleted from SQS (already processed successfully)
- Message 3 retries up to 3 times before going to DLQ

Without this, a single bad message would requeue all 10, causing duplicate processing for the 9 that already succeeded.

---

## 5. Infrastructure created by SAM template

| Resource | Notes |
|----------|-------|
| Lambda | SQS-triggered, BatchSize=10, 60s timeout |
| SQS `payment.incoming` | 24h retention, 3 retries, long polling |
| SQS `payment.incoming.dlq` | 14-day retention for investigation |
| SNS `payment.processed` | Fan-out to notification + analytics |
| DynamoDB `nexus-payment-idempotency` | 24h TTL |
| CW Alarm `nexus-payment-dlq-messages` | Any DLQ message → immediate alert |

---

## 6. Local development

```bash
# 1. Start LocalStack (SQS + SNS + DynamoDB + SecretsManager)
docker compose up -d nexus-localstack

# 2. Build
mvn package -DskipTests
sam build

# 3. Test with Visa payment
sam local invoke PaymentProcessorLambda \
  -e events/visa-payment-event.json \
  --env-vars events/env.json

# 4. Test with Mastercard payment
sam local invoke PaymentProcessorLambda \
  -e events/mastercard-payment-event.json \
  --env-vars events/env.json

# 5. Check idempotency table
aws --endpoint-url=http://localhost:4566 --region=us-east-1 \
  dynamodb scan --table-name nexus-payment-idempotency
```

---

## 7. Deploy

```bash
sam build
sam deploy                       # dev
sam deploy --config-env staging  # staging
sam deploy --config-env prod \
  --parameter-overrides \
    "KafkaBridgeHttpUrl=http://nexus-api-gateway.prod.internal:8080"
```

To switch to MSK in production:
```bash
sam deploy --config-env prod \
  --parameter-overrides \
    "KafkaBridgeMode=MSK" \
    "MskBootstrapServers=b-1.nexus.kafka.us-east-1.amazonaws.com:9092,b-2.nexus.kafka.us-east-1.amazonaws.com:9092"
```

---

## 8. Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID_STAGING` | IAM for staging |
| `AWS_SECRET_ACCESS_KEY_STAGING` | — |
| `AWS_ACCESS_KEY_ID_PROD` | IAM for production |
| `AWS_SECRET_ACCESS_KEY_PROD` | — |
| `KAFKA_BRIDGE_HTTP_URL_PROD` | Production API Gateway URL |
| `MSK_BOOTSTRAP_SERVERS_PROD` | MSK bootstrap servers (empty if using HTTP) |
