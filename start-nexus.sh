#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="docker-compose-prod.yml"
SLEEP_BETWEEN=20      # seconds to wait between starting each service
MAX_RETRIES=10        # health check attempts per service
RETRY_INTERVAL=10     # seconds between health check retries

SERVICES=(
  nexus-zookeeper
  nexus-kafka
  nexus-postgres
  nexus-mongodb
  nexus-redis
  nexus-elasticsearch
  nexus-zipkin
  nexus-prometheus
  nexus-grafana
  nexus-kafdrop
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

# ──────────────────────────────────────────────
# wait_healthy SERVICE
#   Polls docker inspect until the service is
#   "healthy" or "running" (for services with no
#   healthcheck). Retries MAX_RETRIES times with
#   RETRY_INTERVAL seconds between each attempt.
#   Exits the script with a clear message if the
#   service never becomes healthy.
# ──────────────────────────────────────────────
wait_healthy() {
  local service="$1"
  local attempt=1

  echo "  Waiting for $service to become healthy..."

  while [[ $attempt -le $MAX_RETRIES ]]; do
    local health
    health=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$service" 2>/dev/null || echo "not_found")

    case "$health" in
      healthy|running)
        echo "  ✅  $service is $health (attempt $attempt)"
        return 0
        ;;
      unhealthy)
        echo "  ❌  $service is UNHEALTHY (attempt $attempt/$MAX_RETRIES)"
        ;;
      starting)
        echo "  ⏳  $service is still starting (attempt $attempt/$MAX_RETRIES)"
        ;;
      not_found)
        echo "  ⚠️   $service container not found yet (attempt $attempt/$MAX_RETRIES)"
        ;;
      *)
        echo "  ⏳  $service status: $health (attempt $attempt/$MAX_RETRIES)"
        ;;
    esac

    if [[ $attempt -lt $MAX_RETRIES ]]; then
      echo "     Retrying in ${RETRY_INTERVAL}s..."
      sleep "$RETRY_INTERVAL"
    fi

    ((attempt++))
  done

  echo
  echo "══════════════════════════════════════════"
  echo "  ❌  FATAL: $service did not become healthy"
  echo "  after $MAX_RETRIES attempts ($((MAX_RETRIES * RETRY_INTERVAL))s total)."
  echo
  echo "  Check logs:"
  echo "    docker compose -f $COMPOSE_FILE logs --tail=100 $service"
  echo
  echo "  Once fixed, resume from this service:"
  echo "    ./start-nexus.sh $service"
  echo "══════════════════════════════════════════"
  exit 1
}

# ──────────────────────────────────────────────
# Optional: pass a service name as $1 to resume
# the chain from that point.
# ──────────────────────────────────────────────
START_FROM="${1:-${SERVICES[0]}}"

START_INDEX=0
FOUND=false
for i in "${!SERVICES[@]}"; do
  if [[ "${SERVICES[$i]}" == "$START_FROM" ]]; then
    START_INDEX=$i
    FOUND=true
    break
  fi
done

if [[ "$FOUND" == false ]]; then
  echo "Unknown service: $START_FROM"
  echo "Valid services: ${SERVICES[*]}"
  exit 1
fi

echo "══════════════════════════════════════════"
echo " NEXUS PLATFORM — CHAINED STARTUP"
echo " Starting from : ${SERVICES[$START_INDEX]}"
echo " Between services : ${SLEEP_BETWEEN}s gap"
echo " Health retries   : ${MAX_RETRIES} × ${RETRY_INTERVAL}s"
echo "══════════════════════════════════════════"
echo

TOTAL=${#SERVICES[@]}

for ((i=START_INDEX; i<TOTAL; i++)); do
  SERVICE="${SERVICES[$i]}"

  echo "──────────────────────────────────────────"
  echo "[$((i+1))/$TOTAL] ▶  $SERVICE"
  echo "──────────────────────────────────────────"

  # Start the service
  if ! docker compose -f "$COMPOSE_FILE" up -d "$SERVICE"; then
    echo
    echo "  ❌  docker compose up failed for $SERVICE."
    echo "  Fix it, then resume with: ./start-nexus.sh $SERVICE"
    exit 1
  fi

  # Wait until healthy
  wait_healthy "$SERVICE"

  # Gap before the next service (skip after the last one)
  if [[ $((i+1)) -lt $TOTAL ]]; then
    echo "  ⏸   Sleeping ${SLEEP_BETWEEN}s before next service..."
    echo
    sleep "$SLEEP_BETWEEN"
  fi
done

echo
echo "══════════════════════════════════════════"
echo " ✅  ALL SERVICES UP — Final status:"
echo "══════════════════════════════════════════"
docker compose -f "$COMPOSE_FILE" ps