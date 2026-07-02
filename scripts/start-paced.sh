#!/usr/bin/env bash
# Paced startup for docker-compose-prod.yml.
# Infra/dependencies come up first, one by one, no restart trick.
# App services come up one by one: up -> wait 10s -> restart -> wait 30s
# before moving to the next one (first boot is sometimes flaky, the
# restart clears that up).
set -e

cd "$(dirname "$0")/.."

COMPOSE_FILE="docker-compose-prod.yml"
export COMPOSE_PROFILES=core,auth,txn,ai,support,gateway

INFRA_SERVICES=(
  nexus-postgres
  nexus-mongodb
  nexus-redis
  nexus-elasticsearch
  nexus-zipkin
  nexus-prometheus
  nexus-grafana
  nexus-zookeeper
  nexus-kafka
  nexus-kafdrop
  nexus-kafka-connect
  nexus-kafka-topics-init
  nexus-debezium-init
)

APP_SERVICES=(
  nexus-config-service
  nexus-discovery-service
  nexus-identity-service
  nexus-account-service
  nexus-transaction-service
  nexus-fraud-service
  nexus-ledger-service
  nexus-notification-service
  nexus-ai-assistant-service
  nexus-ai-kyc-service
  nexus-analytics-service
  nexus-risk-scoring-service
  nexus-saga-orchestrator
  nexus-audit-query-jvm
  nexus-audit-write-native
  nexus-api-gateway
)

echo "############################################"
echo "# Phase 1: infra / dependencies"
echo "############################################"
for svc in "${INFRA_SERVICES[@]}"; do
  echo ""
  echo "--- up: $svc ---"
  docker compose -f "$COMPOSE_FILE" up -d "$svc"
  sleep 5
done

echo ""
echo "############################################"
echo "# Phase 2: application services (paced)"
echo "############################################"
for svc in "${APP_SERVICES[@]}"; do
  echo ""
  echo "=== $svc ==="
  echo "  up..."
  docker compose -f "$COMPOSE_FILE" up -d "$svc"
  echo "  waiting 10s..."
  sleep 10
  echo "  restart..."
  docker compose -f "$COMPOSE_FILE" restart "$svc"
  echo "  waiting 30s before next service..."
  sleep 30
done

echo ""
echo "############################################"
echo "# Done. Current status:"
echo "############################################"
docker compose -f "$COMPOSE_FILE" ps
