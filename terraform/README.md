# NEXUS Platform — Terraform

Single Terraform root that creates the entire AWS-side architecture for the
8 Lambdas under `../LAMBDA/`, plus the shared IAM user / KYC bucket / KYC
queue that `docker-compose-prod.yml` (Plane A) already depends on.

This folder **replaces `../nexus-infra/`**. `nexus-infra/` was already
`terraform apply`-ed against real AWS (IAM user `nexus-platform-svc`, its
access key, and the two KYC SQS queues exist for real — see "One-time
migration" below). This folder is the new single source of truth going
forward; once the import below is confirmed working, delete `nexus-infra/`.

## What gets created

| File | Resources |
|---|---|
| `iam.tf` | Shared IAM user `nexus-platform-svc` (used by docker-compose services for S3/SQS/SNS) |
| `s3.tf` | KYC documents S3 bucket |
| `sqs.tf` | KYC pending queue + DLQ |
| `secrets.tf` | `nexus/plane-bridge-secret` in Secrets Manager |
| `lambda-auth.tf` | Cognito pool/client, 2 DynamoDB tables, HTTP API Gateway, `nexus-auth-lambda` |
| `lambda-fraud-alert.tf` | SQS queue+DLQ, KMS key, 2 DynamoDB tables, 2 SNS topics, `nexus-fraud-alert-lambda` |
| `lambda-health-monitor.tf` | 3 SNS topics, EventBridge 5-min schedule, `nexus-health-monitor-lambda` |
| `lambda-kyc-rekognition.tf` | SQS results queue+DLQ, S3 trigger on the **existing** KYC bucket, `nexus-kyc-rekognition-lambda` |
| `lambda-notification-dispatcher.tf` | SNS dispatch topic, delivery-status/audit queues, SESv2 config set, `nexus-notification-dispatcher-lambda` |
| `lambda-payment-processor.tf` | SQS queue+DLQ, SNS topic, idempotency table, `nexus-payment-processor-lambda` |
| `lambda-reporting.tf` | S3 reports bucket + KMS, SNS topic, `nexus-reporting-lambda` (reads tables it doesn't own) |
| `lambda-analytics-aggregator.tf` | `nexus-transactions` table (+ streams) + 6 analytics tables, `nexus-analytics-aggregator-lambda` |

## Prerequisites

- Terraform >= 1.6
- AWS CLI configured with credentials that can create IAM/Lambda/DynamoDB/SQS/SNS/S3/Cognito/SES/EventBridge/KMS resources
- **Git Bash on PATH** — the Python Lambda builds run via `local-exec` with `interpreter = ["bash", "-c"]`. On this Windows box that's already true.
- Java 21 + Maven — required to build the 5 Java Lambda jars (`mvn` must be on PATH)
- Python 3.12 + `pip` on PATH — Terraform shells out to `pip install` for the 3 Python Lambdas at apply time

## One-time migration: adopt nexus-infra's real resources

`nexus-infra/terraform.tfstate` shows these are **real, already-created** AWS
resources (account `531948421049`, region `us-east-1`). Run these from
inside `terraform/` **before your first `terraform apply`**, so Terraform
adopts them instead of trying (and failing) to create duplicates:

```bash
cd terraform
terraform init

terraform import aws_iam_user.nexus_platform nexus-platform-svc
terraform import aws_iam_access_key.nexus_platform nexus-platform-svc/<ACCESS_KEY_ID>
terraform import aws_iam_policy.nexus_s3_kyc arn:aws:iam::531948421049:policy/nexus-s3-kyc-policy-prod
terraform import aws_iam_policy.nexus_sqs_kyc arn:aws:iam::531948421049:policy/nexus-sqs-kyc-policy-prod
terraform import aws_iam_user_policy_attachment.nexus_s3 nexus-platform-svc/arn:aws:iam::531948421049:policy/nexus-s3-kyc-policy-prod
terraform import aws_iam_user_policy_attachment.nexus_sqs nexus-platform-svc/arn:aws:iam::531948421049:policy/nexus-sqs-kyc-policy-prod
terraform import aws_sqs_queue.kyc_pending https://sqs.us-east-1.amazonaws.com/531948421049/nexus-kyc-documents-pending
terraform import aws_sqs_queue.kyc_dlq https://sqs.us-east-1.amazonaws.com/531948421049/nexus-kyc-documents-pending-dlq
terraform import aws_sqs_queue_policy.kyc_pending https://sqs.us-east-1.amazonaws.com/531948421049/nexus-kyc-documents-pending
```

**The KYC S3 bucket is NOT in `nexus-infra`'s state** (only IAM + SQS resources
are) even though `s3.tf` defines it — its state was lost at some point. It
**does exist for real** (verified `2026-06-30` — versioning Enabled, AES256
encryption, public access fully blocked, the `kyc-document-retention`
lifecycle rule, CORS, and the deny-HTTP bucket policy all already match
`s3.tf` exactly, so this import should produce zero drift). Import it too:

```bash
terraform import aws_s3_bucket.kyc_documents nexus-kyc-documents
terraform import aws_s3_bucket_public_access_block.kyc_documents nexus-kyc-documents
terraform import aws_s3_bucket_server_side_encryption_configuration.kyc_documents nexus-kyc-documents
terraform import aws_s3_bucket_versioning.kyc_documents nexus-kyc-documents
terraform import aws_s3_bucket_lifecycle_configuration.kyc_documents nexus-kyc-documents
terraform import aws_s3_bucket_cors_configuration.kyc_documents nexus-kyc-documents
terraform import aws_s3_bucket_policy.kyc_documents_deny_http nexus-kyc-documents
```

After the imports, run `terraform plan` — it should show **no changes** (or
only the new Lambda resources being added), not "replace" on anything you
just imported. If it wants to replace/destroy an imported resource, stop and
investigate before applying.

Once you've confirmed `nexus-infra`'s resources are correctly adopted here,
delete the `nexus-infra/` folder.

## Build the Lambdas

```bash
# Java Lambdas — must be built BEFORE `terraform apply` (it just reads the jar)
mvn -f ../LAMBDA/nexus-auth-lambda/nexus-auth-lambda clean package -DskipTests
mvn -f ../LAMBDA/nexus-fraud-alert-lambda/nexus-fraud-alert-lambda clean package -DskipTests
mvn -f ../LAMBDA/nexus-notification-dispatcher-lambda/nexus-notification-dispatcher-lambda clean package -DskipTests
mvn -f ../LAMBDA/nexus-payment-processor-lambda/nexus-payment-processor-lambda clean package -DskipTests
mvn -f ../LAMBDA/nexus-reporting-lambda/nexus-reporting-lambda clean package -DskipTests

# Python Lambdas (health-monitor, kyc-rekognition, analytics-aggregator) —
# Terraform builds these itself via local-exec + pip install on `terraform apply`.
# Nothing to do here manually.
```

## Apply

```bash
terraform plan -var="compliance_team_email=you@nexusbank.com" -var-file="terraform.tfvars"
terraform apply -var="compliance_team_email=you@nexusbank.com" -var-file="terraform.tfvars"
```

`compliance_team_email` has no default (it's where fraud compliance alerts
get emailed) — pass it on the CLI or add it to `terraform.tfvars`.

## Wire the outputs into docker-compose-prod.yml

```bash
terraform output -raw env_block >> ../.env
```

This appends `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `KYC_S3_BUCKET`,
`KYC_SQS_QUEUE_URL`, `KYC_REKOGNITION_RESULTS_QUEUE_URL`,
`SNS_NOTIFICATION_DISPATCH_TOPIC_ARN`, `FRAUD_ALERTS_QUEUE_URL`, and
`PLANE_BRIDGE_SECRET`. Review it before committing — `.env` must never be
committed (already covered by your existing rules).

## Known gaps — read before you "test one by one"

1. **`host.docker.internal` does not work for real AWS Lambda.** The SAM
   templates defaulted `LOCAL_PLANE_*_URL` variables to
   `http://host.docker.internal:<port>`, which only resolves for `sam local`
   running on the same machine. A real Lambda in AWS cannot reach a machine
   behind your home/office NAT. Before testing the Lambdas that call back
   into docker-compose (auth, fraud-alert, health-monitor, payment-processor),
   either:
   - expose docker-compose-prod.yml's `nexus-api-gateway` (and whatever else
     needs to be reachable) through a tunnel (ngrok, Cloudflare Tunnel,
     Tailscale Funnel), and override the corresponding `local_plane_*_url` /
     `kafka_bridge_http_url` Terraform variables with that public URL, or
   - skip cross-plane testing for now and verify each Lambda in isolation
     (its own queue/topic/table/IAM), which doesn't require the bridge.
2. **`SNS_FRAUD_ALERTS_TOPIC_ARN` isn't read anywhere in `nexus-fraud-service` yet.** Its `AwsConfig.java` only wires an `SqsClient`. The fraud-alert Lambda's actual trigger is the SQS queue output as `FRAUD_ALERTS_QUEUE_URL` — that's the integration point to build against, not an SNS publish.
3. **`PLANE_BRIDGE_SECRET` isn't validated anywhere in Plane A yet.** Lambdas send it as the `X-Plane-Bridge-Secret` header; no current docker-compose service checks it. Needs an interceptor/filter wired into whichever service ends up handling `/internal/v1/...` callbacks (identity-service today).
4. **Cognito MFA was simplified to `OFF`** (vs. the SAM template's `OPTIONAL`) in `lambda-auth.tf` — enabling `OPTIONAL` cleanly needs TOTP/SMS configuration that wasn't specified anywhere in the original template.
5. **`ANALYTICS_DYNAMODB_ENABLED=true`** on `nexus-transaction-service` (docker-compose) would start writing directly into the `nexus-transactions` table this Terraform creates — but the shared `nexus-platform-svc` IAM user currently has **no DynamoDB permissions** at all. Flipping that flag needs an additional IAM policy added to `iam.tf` first.
