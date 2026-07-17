#!/usr/bin/env bash
# Scan every NEXUS image locally with Trivy, fail if any CRITICAL/HIGH CVE
# is found. Not wired into CI yet (none exists) — run manually before a
# prod deploy, or add as a pre-push git hook later.
#
# Requires: Trivy installed locally (https://trivy.dev) and the images
# already built (`mvn package` + `docker compose -f docker-compose-prod.yml
# build`). This script does NOT build or start anything itself.
#
# Usage:
#   ./scripts/scan-all.sh                # scan all 16 images
#   ./scripts/scan-all.sh account fraud   # scan a subset (name fragments)
set -e

cd "$(dirname "$0")/.."

IMAGES=(
  nexus/config-service:latest
  nexus/discovery-service:latest
  nexus/identity-service:latest
  nexus/account-service:latest
  nexus/transaction-service:latest
  nexus/fraud-service:latest
  nexus/ledger-service:latest
  nexus/saga-orchestrator:latest
  nexus/ai-assistant-service:latest
  nexus/ai-kyc-service:latest
  nexus/analytics-service:latest
  nexus/notification-service:latest
  nexus/risk-scoring-service:latest
  nexus/audit-query-jvm:latest
  nexus/audit-write-native:latest
  nexus/api-gateway:latest
)

if ! command -v trivy >/dev/null 2>&1; then
  echo "trivy not found. Install: https://trivy.dev/latest/getting-started/installation/" >&2
  exit 1
fi

# Optional filter: ./scan-all.sh account fraud → only images matching those fragments
if [ "$#" -gt 0 ]; then
  filtered=()
  for img in "${IMAGES[@]}"; do
    for frag in "$@"; do
      [[ "$img" == *"$frag"* ]] && filtered+=("$img")
    done
  done
  IMAGES=("${filtered[@]}")
fi

REPORT_DIR="scripts/trivy-reports"
mkdir -p "$REPORT_DIR"

failed=()
for img in "${IMAGES[@]}"; do
  name="${img%%:*}"
  name="${name#nexus/}"
  echo "── Scanning $img ─────────────────────────────────"
  # --exit-code 1 makes Trivy itself fail the script on CRITICAL/HIGH;
  # --ignore-unfixed skips CVEs with no available patch (nothing actionable
  # to do about those today — track them separately, don't block on them).
  if ! trivy image \
      --severity CRITICAL,HIGH \
      --ignore-unfixed \
      --exit-code 1 \
      --format table \
      --output "$REPORT_DIR/$name.txt" \
      "$img"; then
    failed+=("$img")
    cat "$REPORT_DIR/$name.txt"
  fi
done

echo ""
echo "Reports written to $REPORT_DIR/"

if [ "${#failed[@]}" -gt 0 ]; then
  echo ""
  echo "FAILED (CRITICAL/HIGH CVEs found):"
  printf '  - %s\n' "${failed[@]}"
  echo ""
  echo "How to read the output: each row is one CVE — ID, severity, package,"
  echo "installed version, fixed version. Priority order:"
  echo "  1. CRITICAL with a fixed version available — bump the dependency now."
  echo "  2. HIGH with a fixed version available — schedule this sprint."
  echo "  3. Anything with no fixed version yet — track it, can't act on it."
  echo "Base-image CVEs (row's package is glibc/musl/openssl, not a Java dep)"
  echo "mean bumping the eclipse-temurin patch tag; app-level CVEs mean"
  echo "bumping the Maven dependency in that service's pom.xml."
  exit 1
fi

echo "All images clean (no CRITICAL/HIGH with an available fix)."
