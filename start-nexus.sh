#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="docker-compose-prod.yml"
SLEEP_BETWEEN=10      # seconds to wait between starting each service
MAX_RETRIES=60        # health check attempts per service
RETRY_INTERVAL=5      # seconds between health check retries
# Budget = MAX_RETRIES * RETRY_INTERVAL = 300s, unchanged from before.
# The slowest Dockerfile HEALTHCHECK in this platform is
# nexus-ai-assistant-service at --start-period=120s --interval=30s —
# Docker won't even report the container's real health status until
# the start-period elapses AND the first interval-based probe runs
# after that (~120-150s in the worst case), plus JVM/Spring AI startup
# time before actuator/health actually responds 200. Polling every 5s
# instead of 15s just means services that come up fast (most infra)
# get detected sooner, without shrinking the real margin for slow ones.

# ──────────────────────────────────────────────
# Infra always started; monitoring only with --with-monitoring;
# app services only for the profiles you actually request. This
# is what keeps a default run lean instead of booting all 26
# containers — see PROFILES/--profiles below.
# ──────────────────────────────────────────────
INFRA_SERVICES=(
  nexus-kafka
  nexus-kafka-topics-init
  nexus-kafka-connect
  nexus-debezium-init
  nexus-postgres
  nexus-mongodb
  nexus-redis
  nexus-elasticsearch
  nexus-zipkin
)
MONITORING_SERVICES=(
  nexus-postgres-exporter
  nexus-redis-exporter
  nexus-mongodb-exporter
  nexus-elasticsearch-exporter
  nexus-cadvisor
  nexus-pushgateway
  nexus-prometheus
  nexus-loki
  nexus-grafana
  nexus-kafdrop
  nexus-pgadmin
  nexus-mongo-express
  nexus-elasticvue
  nexus-kibana
)
# Run-to-completion containers (kafka-topics-init, debezium-init) have no
# long-running process to healthcheck — wait_healthy's healthy/running
# check would never match "exited (0)" and would falsely FATAL them out.
ONE_SHOT_SERVICES=(
  nexus-kafka-topics-init
  nexus-debezium-init
)
ALL_PROFILES_ORDER=(core auth txn ai support gateway)

profile_services() {
  case "$1" in
    core)    echo "nexus-config-service nexus-discovery-service" ;;
    auth)    echo "nexus-identity-service" ;;
    txn)     echo "nexus-account-service nexus-transaction-service nexus-fraud-service nexus-ledger-service nexus-saga-orchestrator" ;;
    ai)      echo "nexus-ai-assistant-service nexus-ai-kyc-service nexus-analytics-service" ;;
    support) echo "nexus-notification-service nexus-risk-scoring-service nexus-audit-query-jvm nexus-audit-write-native" ;;
    gateway) echo "nexus-api-gateway" ;;
  esac
}

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
  echo "    ./start-nexus.sh --profiles $PROFILES_ARG $service"
  echo "══════════════════════════════════════════"
  exit 1
}

is_one_shot() {
  local service="$1"
  local o
  for o in "${ONE_SHOT_SERVICES[@]}"; do
    [[ "$o" == "$service" ]] && return 0
  done
  return 1
}

# ──────────────────────────────────────────────
# is_healthy_now SERVICE
#   One-shot check (no polling) of a long-running
#   service's current state. Used to skip services
#   that are already up from a previous run instead
#   of re-running `up -d` + the full wait_healthy
#   retry loop for something that's already fine.
# ──────────────────────────────────────────────
is_healthy_now() {
  local service="$1"
  local health
  health=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$service" 2>/dev/null || echo "not_found")
  [[ "$health" == "healthy" || "$health" == "running" ]]
}

# is_one_shot_done SERVICE — true if the init container already ran to completion.
is_one_shot_done() {
  local service="$1"
  local status
  status=$(docker inspect --format='{{.State.Status}} {{.State.ExitCode}}' "$service" 2>/dev/null) || return 1
  [[ "$status" == "exited 0" ]]
}

# ──────────────────────────────────────────────
# run_once SERVICE
#   Runs a one-shot init container (`docker compose up
#   SERVICE`, no -d) and checks its exit code directly —
#   these containers are meant to run once and exit 0,
#   not stay up, so wait_healthy doesn't apply to them.
#   Output is captured to a temp file instead of printed;
#   it's only dumped to the terminal on failure.
# ──────────────────────────────────────────────
run_once() {
  local service="$1"
  local log_file
  log_file=$(mktemp)
  echo -n "  Running one-shot init: $service... "
  if ! docker compose -f "$COMPOSE_FILE" up --abort-on-container-exit --exit-code-from "$service" "$service" >"$log_file" 2>&1; then
    echo "FAILED"
    echo
    echo "══════════════════════════════════════════"
    echo "  ❌  FATAL: $service failed."
    echo
    echo "  Last 50 lines of output:"
    tail -n 50 "$log_file"
    echo
    echo "  Full logs:"
    echo "    docker compose -f $COMPOSE_FILE logs --tail=100 $service"
    echo
    echo "  Once fixed, resume from this service:"
    echo "    ./start-nexus.sh --profiles $PROFILES_ARG $service"
    echo "══════════════════════════════════════════"
    rm -f "$log_file"
    exit 1
  fi
  rm -f "$log_file"
  echo "✅ done"
}

# ──────────────────────────────────────────────
# Args:
#   --reset | -r          full teardown first (see block below)
#   --profiles LIST       comma-separated profiles to start
#                          (default: core,auth — the lean set needed
#                          to log in and hit protected endpoints)
#   --with-monitoring     also start prometheus/grafana/kafdrop
#   SERVICE                bare positional arg — resume the chain
#                          from this service instead of the start
# ──────────────────────────────────────────────
RESET=false
PROFILES_ARG="core,auth"
WITH_MONITORING=false
START_FROM=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --reset|-r)
      RESET=true
      shift
      ;;
    --profiles)
      PROFILES_ARG="$2"
      shift 2
      ;;
    --with-monitoring)
      WITH_MONITORING=true
      shift
      ;;
    *)
      START_FROM="$1"
      shift
      ;;
  esac
done

IFS=',' read -ra REQUESTED_PROFILES <<< "$PROFILES_ARG"
declare -A requested
for p in "${REQUESTED_PROFILES[@]}"; do
  valid=false
  for known in "${ALL_PROFILES_ORDER[@]}"; do
    [[ "$known" == "$p" ]] && valid=true
  done
  if [[ "$valid" == false ]]; then
    echo "Unknown profile: '$p'. Valid: ${ALL_PROFILES_ORDER[*]}"
    exit 1
  fi
  requested[$p]=1
done

# core (config+discovery) is a hard depends_on for every other profile;
# auth (identity) is a hard depends_on for gateway specifically.
if [[ ${#requested[@]} -gt 0 && -z "${requested[core]:-}" ]]; then
  echo "Note: auto-adding 'core' profile — every service depends on config-service/discovery-service."
  requested[core]=1
fi
if [[ -n "${requested[gateway]:-}" && -z "${requested[auth]:-}" ]]; then
  echo "Note: auto-adding 'auth' profile — api-gateway depends on identity-service."
  requested[auth]=1
fi

ORDERED_PROFILES=()
for p in "${ALL_PROFILES_ORDER[@]}"; do
  [[ -n "${requested[$p]:-}" ]] && ORDERED_PROFILES+=("$p")
done

export COMPOSE_PROFILES
COMPOSE_PROFILES=$(IFS=,; echo "${ORDERED_PROFILES[*]}")

SERVICES=("${INFRA_SERVICES[@]}")
[[ "$WITH_MONITORING" == true ]] && SERVICES+=("${MONITORING_SERVICES[@]}")
for p in "${ORDERED_PROFILES[@]}"; do
  SERVICES+=($(profile_services "$p"))
done

# ──────────────────────────────────────────────
# --reset / -r: full, clean teardown before starting.
#
# Kafka runs in KRaft mode with a fixed CLUSTER_ID baked
# into docker-compose-prod.yml. Kafka's data volume stores
# its own copy of that cluster ID (and the KRaft metadata
# log) in meta.properties on first boot. If the volume was
# created by an older Zookeeper-mode container, or by a
# prior KRaft boot with a different CLUSTER_ID, the broker
# refuses to start with InconsistentClusterIdException.
# Removing the data volume guarantees a clean bootstrap
# against the current CLUSTER_ID. This does NOT touch
# Postgres/Mongo volumes, so app data survives.
#
# Reset always tears down ALL profiles (not just the
# ones requested for this run) so nothing from a
# previous wider run is left orphaned on the network.
# ──────────────────────────────────────────────
if [[ "$RESET" == true ]]; then
  echo "══════════════════════════════════════════"
  echo " Full reset — tearing down all containers"
  echo " and resetting the Kafka volume"
  echo " (Postgres/Mongo data is preserved)"
  echo "══════════════════════════════════════════"
  COMPOSE_PROFILES="core,auth,txn,ai,support,gateway" docker compose -f "$COMPOSE_FILE" down
  docker volume rm nexus_kafka-data 2>/dev/null || true
  echo "Reset done."
  echo
fi

[[ -z "$START_FROM" ]] && START_FROM="${SERVICES[0]}"

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
  echo "Unknown service or not in the active profiles: $START_FROM"
  echo "Active services: ${SERVICES[*]}"
  exit 1
fi

echo "══════════════════════════════════════════"
echo " NEXUS PLATFORM — CHAINED STARTUP"
echo " Profiles         : $COMPOSE_PROFILES$( [[ "$WITH_MONITORING" == true ]] && echo " + monitoring")"
echo " Starting from    : ${SERVICES[$START_INDEX]}"
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

  SKIPPED=false

  if is_one_shot "$SERVICE"; then
    if is_one_shot_done "$SERVICE"; then
      echo "  ⏭️   $SERVICE already completed — skipping"
      SKIPPED=true
    else
      run_once "$SERVICE"
    fi
  else
    if is_healthy_now "$SERVICE"; then
      echo "  ⏭️   $SERVICE already up and healthy — skipping"
      SKIPPED=true
    else
      # Start the service (--quiet-pull suppresses image pull progress;
      # the "Container X Started" plan output is harmless but redundant
      # with the [i/TOTAL] header above, so it's dropped too)
      if ! docker compose -f "$COMPOSE_FILE" up -d --quiet-pull "$SERVICE" >/dev/null; then
        echo
        echo "  ❌  docker compose up failed for $SERVICE."
        echo "  Fix it, then resume with: ./start-nexus.sh --profiles $PROFILES_ARG $SERVICE"
        exit 1
      fi

      # Wait until healthy
      wait_healthy "$SERVICE"
    fi
  fi

  # Gap before the next service (skip after the last one, and skip
  # entirely if this service was already up — nothing changed, so
  # there's nothing for the next service to wait out)
  if [[ $((i+1)) -lt $TOTAL && "$SKIPPED" == false ]]; then
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
