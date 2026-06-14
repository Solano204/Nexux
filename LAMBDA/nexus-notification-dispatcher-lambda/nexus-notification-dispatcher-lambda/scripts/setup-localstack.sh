#!/usr/bin/env bash
# Creates SNS topics, SQS queues, and SES identity in LocalStack.
set -euo pipefail
ENDPOINT="http://localhost:4566"
REGION="us-east-1"

echo "Setting up LocalStack for nexus-notification-dispatcher-lambda..."

# SNS dispatch topic
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sns create-topic --name "nexus-notification-dispatch" 2>/dev/null || true

# SQS delivery status queue + DLQ
DLQ_ARN=$(aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs create-queue \
    --queue-name "nexus-delivery-status-dlq" \
    --attributes MessageRetentionPeriod=86400 \
    --query "QueueUrl" --output text 2>/dev/null || true)

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs create-queue \
    --queue-name "nexus-delivery-status" \
    --attributes VisibilityTimeout=60 MessageRetentionPeriod=3600 \
    2>/dev/null || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs create-queue \
    --queue-name "nexus-notification-audit" \
    2>/dev/null || true

# SES verified identity (LocalStack CE accepts any address)
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sesv2 create-email-identity \
    --email-identity "dev-notifications@nexusbank.com" \
    2>/dev/null || true

echo "LocalStack setup complete."
echo ""
echo "Test dispatch:"
echo "  sam local invoke NotificationDispatcherLambda \\"
echo "    -e events/sns-email-event.json \\"
echo "    --env-vars events/env.json"
