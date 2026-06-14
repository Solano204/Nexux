# nexus-reporting-lambda — Complete Dependency & Run Guide

## No source code bugs found

Two things were **missing from the repo**:

| Item | Status |
|------|--------|
| `samconfig.toml` | **NEW** — was missing |
| `.github/workflows/nexus-reporting-lambda.yml` | **NEW** — 6 design-invariant checks |

---

## 1. What this Lambda does

**Trigger:** CloudWatch Events, `cron(0 12 * * ? *)` = 06:00 Mexico City daily  
**Runtime:** Java 21, **SnapStart**, 1024MB, **15-minute timeout**, **2GB ephemeral storage**

**Four report types generated daily:**

| Report | Formats | S3 path | Retention |
|--------|---------|---------|-----------|
| Operations | PDF + JSON + CSV | `daily/operations/` | 90 days |
| Compliance | PDF + CSV | `daily/compliance/` | **7 years** (CNBV) → Glacier |
| Business Intelligence | PDF + CSV | `daily/business-intelligence/` | 1 year |
| User Statements | JSON + CSV | `daily/user-statements/` | 1 year |

**Data sources (read-only, 6 DynamoDB tables):**
- `nexus-transactions` — transaction records
- `nexus-fraud-alerts` — fraud alert history
- `nexus-analytics-daily` — per-user daily aggregates
- `nexus-analytics-category` — spending by category
- `nexus-analytics-hourly-volume` — platform volume by hour
- `nexus-analytics-platform-metrics` — real-time platform metrics

---

## 2. Why the unusual Lambda config

| Setting | Value | Why |
|---------|-------|-----|
| Timeout | 900s (15 min — Lambda max) | PDFBox generates complex multi-page reports; large datasets take minutes |
| Memory | 1024MB | PDFBox and large DynamoDB result sets need headroom |
| EphemeralStorage | 2048MB | PDFBox writes intermediate files to `/tmp`; can be 100s of MB |
| SnapStart | Yes | While cold starts don't matter for daily batch, manual re-runs benefit |

---

## 3. S3 lifecycle rules

Compliance reports follow the strictest path (CNBV 7-year requirement):
```
Day 0-90:   S3 Standard
Day 90-365: S3 Standard-IA   (infrequent access)
Day 365+:   S3 Glacier       (archival)
Forever:    Never deleted     (7-year TTL not implemented as S3 expiry)
```

**Note:** The compliance reports are never auto-deleted by S3 lifecycle. The 7-year retention is enforced by not setting `ExpirationInDays`. Manual deletion is the only way to remove them.

---

## 4. Three CloudWatch alarms

| Alarm | Trigger | What it means |
|-------|---------|---------------|
| `nexus-reporting-failure` | Any Lambda error | Report generation threw an exception |
| `nexus-daily-report-not-generated` | `TreatMissingData: breaching` | The Lambda never ran (schedule disabled, throttled, etc.) |
| `nexus-reporting-duration-high` | Duration > 10 minutes | Approaching timeout — split into Step Functions or increase memory |

The `TreatMissingData: breaching` alarm is the critical one — it alerts if no `ReportGenerationComplete` metric appears within the 24-hour period, catching cases where the Lambda never invoked at all (not just errors during execution).

---

## 5. Local development

```bash
# 1. Start LocalStack
docker compose up -d nexus-localstack
# setup-localstack.sh creates all 6 DynamoDB tables + S3 bucket + SNS
# and seeds a test analytics record

# 2. Build
mvn package -DskipTests
sam build

# 3. Trigger all report types
sam local invoke ReportingLambda \
  -e events/manual-trigger.json \
  --env-vars events/env.json

# 4. Trigger only compliance report for a specific date
sam local invoke ReportingLambda \
  -e events/specific-date-trigger.json \
  --env-vars events/env.json

# 5. Check generated reports
aws --endpoint-url=http://localhost:4566 --region=us-east-1 \
  s3 ls s3://nexus-reports-dev/ --recursive
```

**Note on timeout during local testing:** `sam local invoke` has a 3-second default timeout. For the 15-minute reporting Lambda, pass `--container-env-vars` and ensure Docker has enough resources. Consider testing individual generators in unit tests rather than the full pipeline locally.

---

## 6. Deploy

```bash
sam build
sam deploy                       # dev
sam deploy --config-env staging  # staging
sam deploy --config-env prod     # production
```

**After first deploy:** Confirm the SNS subscription email. An email is sent to `OpsNotificationEmail` — you must click "Confirm subscription" to receive report completion notifications.

---

## 7. Manual re-run (missed report)

To regenerate a report for a specific past date:
```bash
aws lambda invoke \
  --function-name nexus-reporting-lambda \
  --payload '{"reportDate":"2024-01-15","reportTypes":["COMPLIANCE"],"forceRegenerate":true}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/response.json

cat /tmp/response.json
```

---

## 8. Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID_STAGING` | IAM for staging |
| `AWS_SECRET_ACCESS_KEY_STAGING` | — |
| `AWS_ACCESS_KEY_ID_PROD` | IAM for production |
| `AWS_SECRET_ACCESS_KEY_PROD` | — |
| `OPS_NOTIFICATION_EMAIL_PROD` | Operations team email for report completion |
