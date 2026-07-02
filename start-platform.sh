#!/bin/bash
# NEXUS Platform — full startup script
# Usage: bash start-platform.sh

set -e

COMPOSE="docker compose -f docker-compose-prod.yml"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[NEXUS]${NC} $1"; }
warn() { echo -e "${YELLOW}[NEXUS]${NC} $1"; }
err()  { echo -e "${RED}[NEXUS]${NC} $1"; exit 1; }

# Waits until a container's Docker healthcheck reports healthy.
# Tolerates up to 3 consecutive unhealthy readings before giving up —
# this handles the brief window where Kafka topics are auto-creating.
wait_healthy() {
  local container=$1
  local timeout=${2:-240}
  local elapsed=0
  local unhealthy_streak=0
  warn "Waiting for $container..."
  while [ $elapsed -lt $timeout ]; do
    status=$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || echo "missing")
    case $status in
      healthy)
        log "$container is healthy"
        return 0
        ;;
      starting)
        # Still inside start_period — just keep polling
        ;;
      missing)
        warn "$container not found yet, retrying..."
        ;;
      unhealthy)
        unhealthy_streak=$((unhealthy_streak + 1))
        warn "$container is unhealthy (x${unhealthy_streak}/3)..."
        if [ $unhealthy_streak -ge 3 ]; then
          err "$container stayed unhealthy — check logs: docker logs $container"
        fi
        ;;
    esac
    sleep 5
    elapsed=$((elapsed + 5))
  done
  err "$container did not become healthy within ${timeout}s"
}

# ── Step 0: Clean slate ────────────────────────────────────────
log "Cleaning up previous deployment..."
$COMPOSE down -v 2>/dev/null || true
docker container prune -f > /dev/null 2>&1 || true
docker volume rm nexus_kafka-data nexus_zookeeper-data 2>/dev/null || true
log "Cleanup done."

# ── Step 1: Infrastructure ─────────────────────────────────────
log "Starting infrastructure..."
$COMPOSE up -d

wait_healthy nexus-postgres      120
wait_healthy nexus-redis         60
wait_healthy nexus-kafka         120
wait_healthy nexus-kafka-connect 180
wait_healthy nexus-elasticsearch 120
wait_healthy nexus-zipkin        60
wait_healthy nexus-prometheus    60

# Give Kafka 15s to finish auto-creating internal topics so services
# don't hit LEADER_NOT_AVAILABLE on first connect.
log "Kafka warm-up (15s)..."
sleep 15

log "Infrastructure ready."

# ── Step 2: Core (config + discovery) ─────────────────────────
log "Starting core services..."
$COMPOSE --profile core up -d

wait_healthy nexus-config-service    180
wait_healthy nexus-discovery-service 180
log "Core ready."

# ── Step 3: Auth ───────────────────────────────────────────────
log "Starting auth service..."
$COMPOSE --profile core --profile auth up -d

# start_period=90s + up to 5x15s checks = ~165s worst case
wait_healthy nexus-identity-service 240
log "Auth ready."

# ── Step 4: Transaction cluster ────────────────────────────────
log "Starting transaction cluster..."
$COMPOSE --profile core --profile auth --profile txn up -d

wait_healthy nexus-account-service     240
wait_healthy nexus-transaction-service 240
wait_healthy nexus-fraud-service       240
wait_healthy nexus-ledger-service      240
wait_healthy nexus-saga-orchestrator   240
log "Transaction cluster ready."

# ── Step 5: AI cluster ─────────────────────────────────────────
log "Starting AI cluster..."
$COMPOSE --profile core --profile auth --profile txn --profile ai up -d

wait_healthy nexus-ai-assistant-service 240
wait_healthy nexus-ai-kyc-service       240
wait_healthy nexus-analytics-service    240
log "AI cluster ready."

# ── Step 6: Support cluster ────────────────────────────────────
log "Starting support cluster..."
$COMPOSE --profile core --profile auth --profile txn --profile ai --profile support up -d

wait_healthy nexus-notification-service  240
wait_healthy nexus-risk-scoring-service  240
wait_healthy nexus-audit-query-jvm       240
wait_healthy nexus-audit-write-native    240
log "Support cluster ready."

# ── Step 7: API Gateway ────────────────────────────────────────
log "Starting API gateway..."
$COMPOSE --profile core --profile auth --profile txn --profile ai --profile support --profile gateway up -d

wait_healthy nexus-api-gateway 240
log "API Gateway ready."

# ── Done ───────────────────────────────────────────────────────
echo ""
log "=============================="
log " NEXUS Platform is running"
log "=============================="
echo ""
$COMPOSE --profile core --profile auth --profile txn --profile ai --profile support --profile gateway ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"
