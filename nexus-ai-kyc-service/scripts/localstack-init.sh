#!/usr/bin/env bash
# scripts/localstack-init.sh
# Auto-runs when LocalStack finishes starting.
# Creates the S3 bucket used for KYC document uploads.

set -euo pipefail

BUCKET="${KYC_DOCUMENTS_BUCKET:-nexus-kyc-documents}"
ENDPOINT="http://localhost:4566"

echo "🔧  LocalStack init — creating S3 bucket: ${BUCKET}"

aws --endpoint-url="${ENDPOINT}" s3api create-bucket \
    --bucket "${BUCKET}" \
    --region us-east-1 \
    2>/dev/null || echo "    (bucket may already exist)"

aws --endpoint-url="${ENDPOINT}" s3api put-bucket-versioning \
    --bucket "${BUCKET}" \
    --versioning-configuration Status=Enabled

aws --endpoint-url="${ENDPOINT}" s3api put-public-access-block \
    --bucket "${BUCKET}" \
    --public-access-block-configuration \
      "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"

echo "✅  S3 bucket ready: s3://${BUCKET}"
echo ""
# Create a test document for dev testing
echo "placeholder-kyc-document" | \
    aws --endpoint-url="${ENDPOINT}" s3 cp - \
    s3://${BUCKET}/test/test-document.txt 2>/dev/null || true

echo "    S3 buckets:"
aws --endpoint-url="${ENDPOINT}" s3 ls
echo "✅  LocalStack init complete"
