#!/usr/bin/env bash
# scripts/setup-localstack.sh
# Creates all DynamoDB tables and SQS DLQ in LocalStack for local dev.
# Auto-runs when LocalStack starts (placed in ready.d/).

set -euo pipefail

ENDPOINT="http://localhost:4566"
REGION="us-east-1"

echo "🔧  Setting up LocalStack for nexus-analytics-aggregator-lambda"

# Helper: create DynamoDB table with PK+SK key schema
create_table() {
    local name="$1"
    local ttl="${2:-}"

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
        2>/dev/null || echo "    (${name} may already exist)"

    if [ -n "${ttl}" ]; then
        aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
            dynamodb update-time-to-live \
            --table-name "${name}" \
            --time-to-live-specification \
                "Enabled=true,AttributeName=${ttl}" \
            2>/dev/null || true
    fi
}

# ── Source transactions table (with DynamoDB Streams) ───────
echo "📦  Creating nexus-transactions table (with Streams)..."
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb create-table \
    --table-name "nexus-transactions" \
    --attribute-definitions \
        AttributeName=PK,AttributeType=S \
        AttributeName=SK,AttributeType=S \
    --key-schema \
        AttributeName=PK,KeyType=HASH \
        AttributeName=SK,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --stream-specification \
        StreamEnabled=true,StreamViewType=NEW_AND_OLD_IMAGES \
    2>/dev/null || echo "    (nexus-transactions may already exist)"

echo "    ✅  nexus-transactions created with Streams"

# ── Analytics tables ─────────────────────────────────────────
echo "📦  Creating analytics tables..."

create_table "nexus-analytics-daily"              "ttl"
create_table "nexus-analytics-category"           "ttl"
create_table "nexus-analytics-hourly-volume"      "ttl"
create_table "nexus-analytics-merchant-frequency" "ttl"
create_table "nexus-analytics-user-summary"
create_table "nexus-analytics-platform-metrics"

echo "    ✅  All 6 analytics tables created"

# ── SQS DLQ ──────────────────────────────────────────────────
echo "📬  Creating SQS DLQ..."
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs create-queue \
    --queue-name "nexus-analytics-aggregator-dlq" \
    --attributes MessageRetentionPeriod=86400 \
    2>/dev/null || echo "    (DLQ may already exist)"

echo "    ✅  DLQ created"

# ── Verify ───────────────────────────────────────────────────
echo ""
echo "📋  Verification:"
echo "    DynamoDB tables:"
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb list-tables --output text | tr '\t' '\n' | grep nexus || true

echo "    SQS queues:"
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sqs list-queues --output text | grep nexus || true

echo ""
echo "✅  LocalStack setup complete for nexus-analytics-aggregator-lambda"
echo ""
echo "    Next steps:"
echo "    1. sam build"
echo "    2. sam local invoke AnalyticsAggregatorLambda \\"
echo "         -e events/completed_transaction.json \\"
echo "         --env-vars env.json"
