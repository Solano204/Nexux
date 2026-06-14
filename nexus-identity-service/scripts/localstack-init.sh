#!/usr/bin/env bash
# scripts/localstack-init.sh
# Auto-runs when LocalStack finishes starting (ready.d hook).
# Creates the S3 bucket and SQS queue the identity service needs.

set -euo pipefail

AWS_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
BUCKET_NAME="${KYC_DOCUMENTS_BUCKET:-nexus-kyc-documents}"
QUEUE_NAME="nexus-kyc-documents-pending"
DLQ_NAME="nexus-kyc-documents-dlq"

echo "🔧  LocalStack init — creating AWS resources"
echo "    Region: ${AWS_REGION}"
echo "    S3 bucket: ${BUCKET_NAME}"
echo "    SQS queue: ${QUEUE_NAME}"

# Use the LocalStack-internal endpoint
ENDPOINT="http://localhost:4566"

# ── S3 Bucket ──────────────────────────────────────────────────────
echo ""
echo "📦  Creating S3 bucket: ${BUCKET_NAME}"
aws --endpoint-url="${ENDPOINT}" s3api create-bucket \
    --bucket "${BUCKET_NAME}" \
    --region "${AWS_REGION}" \
    2>/dev/null || echo "    (bucket may already exist)"

# Enable versioning (mirrors prod config)
aws --endpoint-url="${ENDPOINT}" s3api put-bucket-versioning \
    --bucket "${BUCKET_NAME}" \
    --versioning-configuration Status=Enabled

# Bucket policy: deny public access
aws --endpoint-url="${ENDPOINT}" s3api put-public-access-block \
    --bucket "${BUCKET_NAME}" \
    --public-access-block-configuration \
      "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"

echo "    ✅ S3 bucket ready: s3://${BUCKET_NAME}"

# ── SQS Dead Letter Queue ──────────────────────────────────────────
echo ""
echo "📬  Creating SQS DLQ: ${DLQ_NAME}"
DLQ_URL=$(aws --endpoint-url="${ENDPOINT}" sqs create-queue \
    --queue-name "${DLQ_NAME}" \
    --attributes MessageRetentionPeriod=1209600 \
    --query QueueUrl \
    --output text)

DLQ_ARN=$(aws --endpoint-url="${ENDPOINT}" sqs get-queue-attributes \
    --queue-url "${DLQ_URL}" \
    --attribute-names QueueArn \
    --query "Attributes.QueueArn" \
    --output text)

echo "    DLQ ARN: ${DLQ_ARN}"

# ── SQS Main Queue ────────────────────────────────────────────────
echo ""
echo "📬  Creating SQS queue: ${QUEUE_NAME}"
QUEUE_URL=$(aws --endpoint-url="${ENDPOINT}" sqs create-queue \
    --queue-name "${QUEUE_NAME}" \
    --attributes \
      VisibilityTimeout=90 \
      MessageRetentionPeriod=86400 \
      ReceiveMessageWaitTimeSeconds=20 \
      RedrivePolicy="{\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"3\"}" \
    --query QueueUrl \
    --output text)

echo "    Queue URL: ${QUEUE_URL}"
echo "    ✅ SQS queue ready"

# ── Verify ────────────────────────────────────────────────────────
echo ""
echo "📋  Verification:"
echo "    S3 buckets:"
aws --endpoint-url="${ENDPOINT}" s3 ls 2>/dev/null | grep "${BUCKET_NAME}" || echo "    (none found)"
echo "    SQS queues:"
aws --endpoint-url="${ENDPOINT}" sqs list-queues \
    --query "QueueUrls[]" \
    --output text 2>/dev/null | tr '\t' '\n' | grep -v "^$" || echo "    (none found)"

echo ""
echo "✅  LocalStack init complete"
