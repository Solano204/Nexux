#!/usr/bin/env bash
# Creates SQS queues, SNS topic, DynamoDB idempotency table, and secret.
set -euo pipefail
ENDPOINT="http://localhost:4566"
REGION="us-east-1"

echo "Setting up LocalStack for nexus-payment-processor-lambda..."

# SQS DLQ first
DLQ_ARN=$(aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs create-queue \
    --queue-name "payment.incoming.dlq" \
    --attributes MessageRetentionPeriod=1209600 \
    --query "QueueUrl" --output text 2>/dev/null || \
    echo "http://localhost:4566/000000000000/payment.incoming.dlq")

# Main payment queue with redrive
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs create-queue \
    --queue-name "payment.incoming" \
    --attributes \
        VisibilityTimeout=70 \
        MessageRetentionPeriod=86400 \
        ReceiveMessageWaitTimeSeconds=20 \
    2>/dev/null || true

# SNS payment.processed topic
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sns create-topic --name "payment.processed" 2>/dev/null || true

# DynamoDB idempotency table
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb create-table \
    --table-name "nexus-payment-idempotency" \
    --attribute-definitions AttributeName=PK,AttributeType=S \
    --key-schema AttributeName=PK,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    2>/dev/null || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb update-time-to-live \
    --table-name nexus-payment-idempotency \
    --time-to-live-specification "Enabled=true,AttributeName=ttl" \
    2>/dev/null || true

# Bridge secret
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    secretsmanager create-secret \
    --name "nexus/plane-bridge-secret" \
    --secret-string '{"secret":"dev-plane-bridge-secret-change-in-prod"}' \
    2>/dev/null || true

echo "LocalStack setup complete."
echo ""
echo "Test:"
echo "  sam local invoke PaymentProcessorLambda \\"
echo "    -e events/visa-payment-event.json \\"
echo "    --env-vars events/env.json"
