#!/usr/bin/env bash
# Creates all AWS resources in LocalStack for local development.
set -euo pipefail
ENDPOINT="http://localhost:4566"
REGION="us-east-1"

echo "Setting up LocalStack for nexus-fraud-alert-lambda..."

# ── KMS key (LocalStack CE uses a fake key) ──────────────────
KMS_KEY_ID=$(aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    kms create-key --description "nexus-fraud-alerts-key" \
    --query "KeyMetadata.KeyId" --output text 2>/dev/null || echo "local-kms-key")

echo "KMS key: ${KMS_KEY_ID}"

# ── nexus-fraud-alerts table ─────────────────────────────────
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb create-table \
    --table-name "nexus-fraud-alerts" \
    --attribute-definitions \
        AttributeName=PK,AttributeType=S \
        AttributeName=SK,AttributeType=S \
        AttributeName=GSI_USER_PK,AttributeType=S \
        AttributeName=GSI_USER_SK,AttributeType=S \
        AttributeName=GSI_DATE_PK,AttributeType=S \
        AttributeName=GSI_DATE_SK,AttributeType=S \
        AttributeName=GSI_STATUS_PK,AttributeType=S \
        AttributeName=GSI_STATUS_SK,AttributeType=S \
    --key-schema \
        AttributeName=PK,KeyType=HASH \
        AttributeName=SK,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --global-secondary-indexes '[
        {"IndexName":"UserIndex","KeySchema":[{"AttributeName":"GSI_USER_PK","KeyType":"HASH"},{"AttributeName":"GSI_USER_SK","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}},
        {"IndexName":"DateIndex","KeySchema":[{"AttributeName":"GSI_DATE_PK","KeyType":"HASH"},{"AttributeName":"GSI_DATE_SK","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}},
        {"IndexName":"StatusIndex","KeySchema":[{"AttributeName":"GSI_STATUS_PK","KeyType":"HASH"},{"AttributeName":"GSI_STATUS_SK","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}
    ]' \
    2>/dev/null || echo "(nexus-fraud-alerts may already exist)"

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb update-time-to-live \
    --table-name nexus-fraud-alerts \
    --time-to-live-specification "Enabled=true,AttributeName=ttl" 2>/dev/null || true

# ── nexus-sar-considerations table ───────────────────────────
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb create-table \
    --table-name "nexus-sar-considerations" \
    --attribute-definitions \
        AttributeName=PK,AttributeType=S \
        AttributeName=SK,AttributeType=S \
    --key-schema \
        AttributeName=PK,KeyType=HASH \
        AttributeName=SK,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    2>/dev/null || echo "(nexus-sar-considerations may already exist)"

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb update-time-to-live \
    --table-name nexus-sar-considerations \
    --time-to-live-specification "Enabled=true,AttributeName=ttl" 2>/dev/null || true

# ── SQS queues ───────────────────────────────────────────────
DLQ_URL=$(aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs create-queue \
    --queue-name "nexus-fraud-alerts-dlq" \
    --attributes MessageRetentionPeriod=604800 \
    --query QueueUrl --output text 2>/dev/null || true)

DLQ_ARN=$(aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs get-queue-attributes \
    --queue-url "${DLQ_URL}" \
    --attribute-names QueueArn \
    --query "Attributes.QueueArn" --output text 2>/dev/null || \
    echo "arn:aws:sqs:us-east-1:000000000000:nexus-fraud-alerts-dlq")

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs create-queue \
    --queue-name "nexus-fraud-alerts-high-severity" \
    --attributes \
        VisibilityTimeout=60 \
        MessageRetentionPeriod=86400 \
        ReceiveMessageWaitTimeSeconds=5 \
        "RedrivePolicy={\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"2\"}" \
    2>/dev/null || echo "(queue may already exist)"

# ── SNS topics ───────────────────────────────────────────────
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sns create-topic --name "nexus-fraud-compliance-alerts" 2>/dev/null || true
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sns create-topic --name "nexus-security-operations" 2>/dev/null || true

# ── Secrets Manager ─────────────────────────────────────────
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    secretsmanager create-secret \
    --name "nexus-josue/plane-bridge-secret" \
    --secret-string '{"secret":"dev-plane-bridge-secret-change-in-prod"}' \
    2>/dev/null || true

echo "LocalStack setup complete for nexus-fraud-alert-lambda."
echo ""
echo "Test with:"
echo "  sam local invoke FraudAlertLambda \\"
echo "    -e events/fraud-alert-event.json \\"
echo "    --env-vars events/env.json"
