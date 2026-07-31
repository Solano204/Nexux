#!/usr/bin/env bash
# Start/stop/restart ONLY the NEXUS application services (Spring Boot +
# Quarkus containers) via docker-compose-prod.yml, without touching
# infra/dependencies (postgres, mongo, redis, kafka, elasticsearch, zipkin,
# prometheus, grafana, loki, kafdrop). Containers must already exist
# (created via scripts/start-paced.sh or `docker compose up`) - this only
# stops/starts them, it never creates, recreates, or removes anything.
#
# Usage:
#   ./scripts/app-services.sh stop      # turn off just the app services
#   ./scripts/app-services.sh start     # turn them back on
#   ./scripts/app-services.sh restart   # stop + start
#   ./scripts/app-services.sh status    # show only the app services
set -e

cd "$(dirname "$0")/.."

COMPOSE_FILE="docker-compose-prod.yml"

# Startup order matches CLAUDE.md: config -> discovery -> identity -> rest -> gateway.
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

reverse_services() {
  for ((i = ${#APP_SERVICES[@]} - 1; i >= 0; i--)); do
    echo "${APP_SERVICES[i]}"
  done
}

cmd="${1:-}"

case "$cmd" in
  stop)
    echo "Stopping app services (gateway first, config last)..."
    docker compose -f "$COMPOSE_FILE" stop $(reverse_services)
    ;;
  start)
    echo "Starting app services (config first, gateway last)..."
    docker compose -f "$COMPOSE_FILE" start "${APP_SERVICES[@]}"
    ;;
  restart)
    echo "Restarting app services..."
    docker compose -f "$COMPOSE_FILE" stop $(reverse_services)
    docker compose -f "$COMPOSE_FILE" start "${APP_SERVICES[@]}"
    ;;
  status)
    docker compose -f "$COMPOSE_FILE" ps "${APP_SERVICES[@]}"
    ;;
  *)
    echo "Usage: $0 {start|stop|restart|status}"
    exit 1
    ;;
esac
