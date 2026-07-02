#!/usr/bin/env bash
set -euo pipefail
ENDPOINT="http://localhost:4566"
REGION="us-east-1"

echo "Setting up LocalStack for nexus-auth-lambda..."

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb create-table \
    --table-name "nexus-sessions" \
    --attribute-definitions \
        AttributeName=PK,AttributeType=S \
        AttributeName=SK,AttributeType=S \
        AttributeName=GSI_JTI,AttributeType=S \
        AttributeName=cognitoSub,AttributeType=S \
    --key-schema \
        AttributeName=PK,KeyType=HASH \
        AttributeName=SK,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --global-secondary-indexes '[
        {"IndexName":"JtiIndex","KeySchema":[{"AttributeName":"GSI_JTI","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}},
        {"IndexName":"CognitoSubIndex","KeySchema":[{"AttributeName":"cognitoSub","KeyType":"HASH"}],"Projection":{"ProjectionType":"ALL"}}
    ]' \
    2>/dev/null || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb update-time-to-live \
    --table-name nexus-sessions \
    --time-to-live-specification "Enabled=true,AttributeName=ttl" 2>/dev/null || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb create-table \
    --table-name "nexus-revoked-tokens" \
    --attribute-definitions AttributeName=PK,AttributeType=S \
    --key-schema AttributeName=PK,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    2>/dev/null || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    dynamodb update-time-to-live \
    --table-name nexus-revoked-tokens \
    --time-to-live-specification "Enabled=true,AttributeName=ttl" 2>/dev/null || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    secretsmanager create-secret \
    --name "nexus/plane-bridge-secret" \
    --secret-string '{"secret":"dev-plane-bridge-secret-change-in-prod"}' \
    2>/dev/null || true

echo "LocalStack setup complete."
