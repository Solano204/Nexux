# nexus-fraud-alert-lambda — Complete Dependency & Run Guide

## No source code bugs found

Two things were **missing from the repo**:

| Item | Status |
|------|--------|
| `samconfig.toml` | **NEW** — required for `sam deploy`; `ComplianceTeamEmail` is a required parameter |
| `.github/workflows/nexus-fraud-alert-lambda.yml` | **NEW** — no CI existed |

---

## 1. What this Lambda does

**Trigger:** SQS queue `nexus-fraud-alerts-high-severity` (only alerts with score ≥ 85)  
**Runtime:** Java 21 with **SnapStart** (~300ms cold start)

**Processing pipeline per message (10 steps):**
1. Parse `FraudAlertEvent` from SQS body
2. Idempotency check — DynamoDB existence check on `alertId`
3. `AlertClassifier` — severity tier + alert category
4. Store to `nexus-fraud-alerts` (KMS-encrypted, PITR-enabled)
5. `SarEvaluator` — CNBV 15-day SAR deadline check
6. Store SAR consideration if triggered
7. Emit CloudWatch metrics (done BEFORE notifications — never blocked)
8. Notify compliance team via SNS
9. Notify security ops via SNS
10. Update DynamoDB with SNS message IDs

---

## 2. Critical: ComplianceTeamEmail is a REQUIRED parameter

The SAM template has a `ComplianceTeamEmail` **parameter with no default**. You must provide it on every deploy, either via `samconfig.toml` (already done) or `--parameter-overrides`.

```bash
# If you want to change it at deploy time:
sam deploy --config-env prod \
  --parameter-overrides "ComplianceTeamEmail=compliance@nexusbank.com"
```

The CI workflow passes it via `${{ secrets.COMPLIANCE_EMAIL_PROD }}` — add this secret to your GitHub repo before running the pipeline.

---

## 3. How this differs from nexus-auth-lambda

| Property | nexus-auth-lambda | nexus-fraud-alert-lambda |
|----------|------------------|--------------------------|
| Trigger | HTTP API Gateway | SQS queue |
| Batch | Single request | Up to 5 messages |
| Concurrency | Unlimited | **ReservedConcurrentExecutions: 20** |
| DLQ after | N/A | **2 failures** (not the usual 3) |
| Storage | DynamoDB (basic) | **KMS-encrypted + PITR** |
| Failure impact | User sees 500 | Missed compliance notification |
| CloudWatch alarms | 0 | **3 alarms** |

The `maxReceiveCount=2` (not the usual 3) is intentional — fraud alerts failing twice means something is seriously wrong, and compliance needs to know immediately rather than after a third retry.

---

## 4. SAR Evaluation — CNBV regulatory context

`SarEvaluator` runs on every high-severity alert and checks for:
- `STRUCTURING` — multiple sub-threshold transactions
- `LAYERING` — complex transaction chains
- `ACCOUNT_TAKEOVER` — device/location anomaly + high velocity
- `AML` — Anti-Money Laundering patterns

If triggered: creates a `nexus-sar-considerations` record with a **15-day deadline** (CNBV / Mexican banking regulator requirement). The compliance team receives an SNS notification with the SAR consideration ID and deadline timestamp.

---

## 5. Infrastructure created by SAM template

| Resource | Name | Notes |
|----------|------|-------|
| Lambda | `nexus-fraud-alert-lambda` | SnapStart, 20 reserved slots |
| SQS queue | `nexus-fraud-alerts-high-severity` | BatchSize=5, maxReceive=2 |
| SQS DLQ | `nexus-fraud-alerts-dlq` | 7-day retention for forensics |
| DynamoDB | `nexus-fraud-alerts` | KMS + PITR + 3 GSIs + 7yr TTL |
| DynamoDB | `nexus-sar-considerations` | TTL |
| KMS key | — | Dedicated key, auto-rotate |
| SNS | `nexus-fraud-compliance-alerts` | Email to ComplianceTeamEmail |
| SNS | `nexus-security-operations` | Security ops webhook |
| CW Alarm | `nexus-fraud-alert-processing-errors` | Any Lambda error → page |
| CW Alarm | `nexus-fraud-alert-dlq-messages` | Any DLQ message → IMMEDIATE |
| CW Alarm | `nexus-fraud-high-severity-spike` | 10+ in 5 min → attack campaign |

---

## 6. Local development

```bash
# 1. Start LocalStack (creates all 9 resources above locally)
docker compose up -d nexus-localstack

# 2. Build
mvn package -DskipTests

# 3. Test with a sample fraud alert
sam local invoke FraudAlertLambda \
  -e events/fraud-alert-event.json \
  --env-vars events/env.json

# 4. Check DynamoDB result
aws --endpoint-url=http://localhost:4566 --region=us-east-1 \
  dynamodb scan --table-name nexus-fraud-alerts
```

---

## 7. Deploy to AWS

```bash
# First-time: create S3 artifact buckets
aws s3 mb s3://nexus-sam-artifacts-dev
aws s3 mb s3://nexus-sam-artifacts-prod

# Build
mvn package -DskipTests
sam build

# Deploy (ComplianceTeamEmail already in samconfig.toml)
sam deploy                       # dev
sam deploy --config-env prod     # production — requires confirmation
```

---

## 8. Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID_STAGING` | IAM credentials for staging |
| `AWS_SECRET_ACCESS_KEY_STAGING` | — |
| `AWS_ACCESS_KEY_ID_PROD` | IAM credentials for production |
| `AWS_SECRET_ACCESS_KEY_PROD` | — |
| `COMPLIANCE_EMAIL_STAGING` | Compliance team email for staging alerts |
| `COMPLIANCE_EMAIL_PROD` | Compliance team email for production alerts |

---

## 9. Monitoring

Three CloudWatch alarms are created automatically:

1. **`nexus-fraud-alert-processing-errors`** — ANY Lambda error → page immediately. Fraud alerts failing = compliance team not notified.
2. **`nexus-fraud-alert-dlq-messages`** — ANY message in DLQ → IMMEDIATE investigation. These are high-severity events that failed twice.
3. **`nexus-fraud-high-severity-spike`** — 10+ alerts in 5 minutes → active coordinated attack campaign. Notify security operations center.

All three should be wired to an SNS topic that pages on-call.
