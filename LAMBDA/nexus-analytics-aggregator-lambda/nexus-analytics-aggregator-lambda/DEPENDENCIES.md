# nexus-analytics-aggregator-lambda — Complete Dependency & Run Guide

## No source code bugs found

The Python source code is correct. No IntelliJ placeholders, no empty configs, no misnamed directories. Two things were **missing from the repo** and have been created:

| Item | Status |
|------|--------|
| `samconfig.toml` | **NEW** — required for `sam deploy`; without it SAM prompts interactively for every parameter on every deploy |
| `.github/workflows/nexus-analytics-aggregator-lambda.yml` | **NEW** — no CI existed |

---

## 1. What this Lambda does

**Trigger:** DynamoDB Streams on `nexus-transactions` table (INSERT and MODIFY events only — DELETE filtered at trigger level).

**Pipeline per stream record:**
1. Deserialize `NewImage` + `OldImage` from typed DynamoDB JSON
2. Filter non-transaction records
3. Detect state transition (COMPLETED, REVERSED, FAILED, NEW_TRANSACTION)
4. Dispatch to 6 aggregators simultaneously (all use atomic DynamoDB ADD)
5. Emit CloudWatch metrics per batch

**6 aggregators and what they write:**

| Aggregator | Table | Key pattern |
|-----------|-------|-------------|
| `daily_stats` | `nexus-analytics-daily` | `PK=USER#{userId}#SK=DAILY#{date}` |
| `category_stats` | `nexus-analytics-category` | `PK=USER#{userId}#SK=CAT#{yearMonth}#{category}` |
| `hourly_volume` | `nexus-analytics-hourly-volume` | `PK=PLATFORM#SK=HOUR#{hour}` |
| `merchant_frequency` | `nexus-analytics-merchant-frequency` | `PK=USER#{userId}#SK=MERCHANT#{merchantId}` |
| `user_summary` | `nexus-analytics-user-summary` | `PK=USER#{userId}#SK=SUMMARY` |
| `platform_metrics` | `nexus-analytics-platform-metrics` | `PK=PLATFORM#SK=REALTIME` |

**Idempotency:** All aggregators check `lastSequenceNumber` — duplicate stream records (at-least-once delivery) are silently skipped.

**Concurrency safety:** All increments use DynamoDB `ADD` — never read-modify-write. Multiple Lambda invocations updating the same item are safe.

---

## 2. What you need installed

| Tool | Why |
|------|-----|
| Python 3.12 | Local development and testing |
| AWS SAM CLI | Build, local invoke, deploy |
| Docker Desktop | `sam local invoke`, LocalStack |
| AWS CLI v2 | LocalStack verification |

```bash
# Verify
python3 --version   # must be 3.12
sam --version       # must be 1.x or 2.x
docker ps           # must be running
```

---

## 3. Local development workflow

### Option A — `sam local invoke` (simplest)

```bash
# 1. Start LocalStack (creates all DynamoDB tables automatically)
docker compose up -d nexus-localstack

# 2. Wait for LocalStack to be ready
curl http://localhost:4566/_localstack/health

# 3. Install Python deps
pip install -r requirements.txt

# 4. Build the Lambda package
sam build

# 5. Invoke with a sample event (against LocalStack)
sam local invoke AnalyticsAggregatorLambda \
    -e events/completed_transaction.json \
    --env-vars events/env.json

# 6. Check the DynamoDB result
aws --endpoint-url=http://localhost:4566 --region=us-east-1 \
    dynamodb scan --table-name nexus-analytics-daily
```

### Option B — pytest with moto mocks (fastest iteration)

```bash
pip install pytest moto[dynamodb,sqs] freezegun
pytest tests/ -v
```

Moto mocks all AWS services in-process — no LocalStack needed.

---

## 4. Deploy to AWS

### First-time setup

```bash
# Create S3 bucket for SAM artifacts (one-time per environment)
aws s3 mb s3://nexus-sam-artifacts-dev --region us-east-1
aws s3 mb s3://nexus-sam-artifacts-staging --region us-east-1
aws s3 mb s3://nexus-sam-artifacts-prod --region us-east-1
```

### Deploy

```bash
cd nexus-analytics-aggregator-lambda

# Build
sam build

# Deploy to dev (prompts for confirmation)
sam deploy

# Deploy to staging (no confirmation)
sam deploy --config-env staging

# Deploy to production (prompts for confirmation — always)
sam deploy --config-env prod
```

### See what's deployed

```bash
# Stack outputs
aws cloudformation describe-stacks \
    --stack-name nexus-analytics-aggregator-prod \
    --query "Stacks[0].Outputs"

# Lambda function status
aws lambda get-function \
    --function-name nexus-analytics-aggregator-lambda

# Check DLQ for failed records
aws sqs get-queue-attributes \
    --queue-url $(aws sqs get-queue-url \
        --queue-name nexus-analytics-aggregator-dlq \
        --query QueueUrl --output text) \
    --attribute-names ApproximateNumberOfMessages
```

---

## 5. Required GitHub Secrets

Add to your repo (Settings → Secrets and variables → Actions):

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID_STAGING` | IAM credentials for staging account |
| `AWS_SECRET_ACCESS_KEY_STAGING` | — |
| `AWS_ACCESS_KEY_ID_PROD` | IAM credentials for production account |
| `AWS_SECRET_ACCESS_KEY_PROD` | — |

The IAM role needs: `AWSLambdaFullAccess`, `AmazonDynamoDBFullAccess`, `AmazonSQSFullAccess`, `CloudFormationFullAccess`, `IAMFullAccess` (for SAM to create roles), `AmazonS3FullAccess` (for artifact bucket).

---

## 6. DynamoDB table TTL summary

| Table | TTL field | Retention |
|-------|-----------|-----------|
| `nexus-analytics-daily` | `ttl` | 90 days |
| `nexus-analytics-category` | `ttl` | 12 months |
| `nexus-analytics-hourly-volume` | `ttl` | 30 days |
| `nexus-analytics-merchant-frequency` | `ttl` | 35 days |
| `nexus-analytics-user-summary` | none | Permanent (1 row/user) |
| `nexus-analytics-platform-metrics` | none | Permanent (REALTIME row) |

---

## 7. Error handling and DLQ

- Any exception in `process_record()` is re-raised → triggers DynamoDB Streams retry
- `BisectBatchOnFunctionError: true` → on failure, splits the batch in half and retries each
- Ultimately isolates a bad record to a batch of 1 → sends to `nexus-analytics-aggregator-dlq`
- CloudWatch alarm `nexus-analytics-aggregation-errors` fires on first error

To inspect DLQ messages:
```bash
aws sqs receive-message \
    --queue-url "https://sqs.us-east-1.amazonaws.com/ACCOUNT_ID/nexus-analytics-aggregator-dlq" \
    --max-number-of-messages 10
```

---

## 8. Monitoring

CloudWatch metrics emitted (namespace `Nexus/Analytics`):
- `TransactionsCompleted` — tagged by Currency, Network
- `TransactionVolume` — tagged by Currency, Category
- `TransactionsReversed` — tagged by Currency
- `TransactionsFailed` — tagged by Currency, Network
- `AggregationBatchDuration` — P95 Lambda processing time per batch

CloudWatch alarm: `nexus-analytics-aggregation-errors` — fires on first Lambda error.
