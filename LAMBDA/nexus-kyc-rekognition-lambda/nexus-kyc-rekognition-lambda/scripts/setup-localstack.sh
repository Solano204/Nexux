#!/usr/bin/env bash
# Creates S3 bucket and SQS queues in LocalStack for local dev.
set -euo pipefail
ENDPOINT="http://localhost:4566"
REGION="us-east-1"

echo "Setting up LocalStack for nexus-kyc-rekognition-lambda..."

# KYC documents bucket (account-ID suffix in prod; use fixed name for dev)
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    s3api create-bucket --bucket "nexus-kyc-documents-000000000000" \
    2>/dev/null || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    s3api put-public-access-block \
    --bucket "nexus-kyc-documents-000000000000" \
    --public-access-block-configuration \
      "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" \
    2>/dev/null || true

# Results queue + DLQ
DLQ_URL=$(aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs create-queue \
    --queue-name "nexus-kyc-rekognition-results-dlq" \
    --attributes MessageRetentionPeriod=86400 \
    --query QueueUrl --output text 2>/dev/null || \
    echo "http://localhost:4566/000000000000/nexus-kyc-rekognition-results-dlq")

DLQ_ARN=$(aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs get-queue-attributes \
    --queue-url "${DLQ_URL}" \
    --attribute-names QueueArn \
    --query "Attributes.QueueArn" --output text 2>/dev/null || \
    echo "arn:aws:sqs:us-east-1:000000000000:nexus-kyc-rekognition-results-dlq")

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs create-queue \
    --queue-name "nexus-kyc-rekognition-results" \
    --attributes \
        VisibilityTimeout=300 \
        MessageRetentionPeriod=3600 \
        "RedrivePolicy={\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"3\"}" \
    2>/dev/null || true

echo "LocalStack setup complete."
echo ""
echo "Upload a test document:"
echo "  aws --endpoint-url=http://localhost:4566 s3 cp /path/to/id.jpg \\"
echo "    s3://nexus-kyc-documents-000000000000/kyc/user-123/verification-456/id.jpg"
echo ""
echo "Invoke Lambda:"
echo "  sam local invoke KycRekognitionLambda \\"
echo "    -e events/s3-trigger.json \\"
echo "    --env-vars events/env.json"
