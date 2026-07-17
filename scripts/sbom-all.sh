#!/usr/bin/env bash
# Generate a CycloneDX SBOM per NEXUS image with Syft, so that the day a
# CVE like Log4Shell drops, "am I affected?" is a grep over
# scripts/sbom-reports/*.json instead of re-scanning 16 running services
# under time pressure.
#
# Requires: Syft installed locally (https://github.com/anchore/syft) and
# the images already built. This script does NOT build or start anything
# itself.
#
# Usage:
#   ./scripts/sbom-all.sh                 # SBOM for all 16 images
#   ./scripts/sbom-all.sh account fraud    # subset (name fragments)
#
# To answer "am I affected by CVE-2021-44228 (Log4Shell)?" later:
#   grep -l "log4j-core" scripts/sbom-reports/*.json
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

if ! command -v syft >/dev/null 2>&1; then
  echo "syft not found. Install: https://github.com/anchore/syft#installation" >&2
  exit 1
fi

if [ "$#" -gt 0 ]; then
  filtered=()
  for img in "${IMAGES[@]}"; do
    for frag in "$@"; do
      [[ "$img" == *"$frag"* ]] && filtered+=("$img")
    done
  done
  IMAGES=("${filtered[@]}")
fi

REPORT_DIR="scripts/sbom-reports"
mkdir -p "$REPORT_DIR"

for img in "${IMAGES[@]}"; do
  name="${img%%:*}"
  name="${name#nexus/}"
  echo "── SBOM: $img ─────────────────────────────────"
  syft "$img" -o cyclonedx-json="$REPORT_DIR/$name.json"
done

echo ""
echo "SBOMs written to $REPORT_DIR/ (CycloneDX JSON, one per image)."
echo "Next CVE fire drill: grep -l \"<package-name>\" $REPORT_DIR/*.json"
