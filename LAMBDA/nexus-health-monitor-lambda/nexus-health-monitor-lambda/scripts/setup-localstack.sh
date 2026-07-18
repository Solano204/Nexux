#!/usr/bin/env bash
set -euo pipefail
ENDPOINT="http://localhost:4566"
REGION="us-east-1"

echo "Setting up LocalStack for nexus-health-monitor-lambda..."

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sns create-topic --name "nexus-health-alerts-critical" 2>/dev/null || true
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sns create-topic --name "nexus-health-alerts-standard" 2>/dev/null || true
aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    sns create-topic --name "nexus-health-monitor-selfwatch" 2>/dev/null || true

aws --endpoint-url="${ENDPOINT}" --region="${REGION}" \
    secretsmanager create-secret \
    --name "nexus-josue/plane-bridge-secret" \
    --secret-string '{"secret":"dev-bridge-secret"}' \
    2>/dev/null || true

echo "LocalStack setup complete."
echo "Run: sam local invoke HealthMonitorLambda -e events/manual-trigger.json --env-vars events/env.json"
