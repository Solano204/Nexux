#!/usr/bin/env bash
# Creates all LocalStack resources for nexus-reporting-lambda local dev.
set -euo pipefail
ENDPOINT="http://localhost:4566"
REGION="us-east-1"

echo "Setting up LocalStack for nexus-reporting-lambda..."

# ── DynamoDB source tables ────────────────────────────────────
create_table() {
    local name="$1"
    aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
        dynamodb create-table \
        --table-name "${name}" \
        --attribute-definitions \
            AttributeName=PK,AttributeType=S \
            AttributeName=SK,AttributeType=S \
        --key-schema \
            AttributeName=PK,KeyType=HASH \
            AttributeName=SK,KeyType=RANGE \
        --billing-mode PAY_PER_REQUEST \
        2>/dev/null || echo "  (${name} may exist)"
}

create_table "nexus-transactions"
create_table "nexus-fraud-alerts"
create_table "nexus-analytics-daily"
create_table "nexus-analytics-category"
create_table "nexus-analytics-hourly-volume"
create_table "nexus-analytics-platform-metrics"

echo "  ✅ DynamoDB tables ready"

# ── Reports S3 bucket ─────────────────────────────────────────
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    s3api create-bucket --bucket "nexus-reports-dev" \
    2>/dev/null || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    s3api put-public-access-block \
    --bucket "nexus-reports-dev" \
    --public-access-block-configuration \
      "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" \
    2>/dev/null || true

echo "  ✅ S3 bucket ready: nexus-reports-dev"

# ── SNS completion topic ──────────────────────────────────────
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sns create-topic --name "nexus-report-completed" 2>/dev/null || true

echo "  ✅ SNS topic ready"

# Seed some test analytics data for report generation
echo ""
echo "  Seeding test analytics data..."
TODAY=$(date -u +%Y-%m-%d)
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb put-item \
    --table-name "nexus-analytics-platform-metrics" \
    --item '{
        "PK":{"S":"PLATFORM"},
        "SK":{"S":"REALTIME"},
        "totalTransactions":{"N":"1250"},
        "totalVolumeMXN":{"N":"3750000.00"},
        "activeUsers":{"N":"892"},
        "date":{"S":"'"${TODAY}"'"}
    }' 2>/dev/null || true

echo "  ✅ Test data seeded"
echo ""
echo "LocalStack setup complete."
echo ""
echo "Manually trigger report generation:"
echo "  sam local invoke ReportingLambda \\"
echo "    -e events/manual-trigger.json \\"
echo "    --env-vars events/env.json"
echo ""
echo "Check reports in S3:"
echo "  aws --endpoint-url=http://localhost:4566 s3 ls s3://nexus-reports-dev/ --recursive"
