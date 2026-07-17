#!/usr/bin/env bash
# Build, tag, and push all 16 NEXUS images to Amazon ECR. Replaces `latest`
# with a reproducible tag so a bad deploy can roll back to the exact
# previous image instead of hoping `latest` still points at something sane.
#
# Versioning scheme: every image gets TWO tags on every run —
#   <git-short-sha>   e.g. a3f9c21   — always present, fully automatic,
#                                      exact provenance (`git show a3f9c21`
#                                      tells you precisely what's running).
#   <semver>          e.g. 1.4.2    — only if you pass VERSION=1.4.2, for
#                                      human-readable "what release is this"
#                                      on top of the commit hash. Optional —
#                                      skip it for routine same-day iteration,
#                                      set it when you actually cut a release.
# `latest` is intentionally NOT pushed — every consumer (docker-compose-prod.yml,
# a future ECS task def, etc.) should pin an explicit tag.
#
# Requires: AWS CLI configured (`aws configure` or env vars), an ECR repo
# per service already created, and the jars already built (`mvn package`
# from repo root per CLAUDE.md). This script does NOT run mvn and does NOT
# start any container — it only builds images and pushes them.
#
# Usage:
#   AWS_ACCOUNT_ID=123456789012 AWS_REGION=us-east-1 ./scripts/build-tag-push-ecr.sh
#   AWS_ACCOUNT_ID=... AWS_REGION=... VERSION=1.4.2 ./scripts/build-tag-push-ecr.sh account fraud
set -e

cd "$(dirname "$0")/.."

: "${AWS_ACCOUNT_ID:?Set AWS_ACCOUNT_ID (docker outputs -> aws sts get-caller-identity)}"
: "${AWS_REGION:?Set AWS_REGION (e.g. us-east-1)}"

ECR_REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
GIT_SHA="$(git rev-parse --short HEAD)"
DIRTY=""
git diff --quiet || DIRTY="-dirty"
TAG="${GIT_SHA}${DIRTY}"

# service-name -> (build-context, dockerfile-path-relative-to-context)
declare -A SERVICES=(
  [config-service]="nexus-config-service:Dockerfile"
  [discovery-service]="nexus-discovery-service:Dockerfile"
  [identity-service]="nexus-identity-service:Dockerfile"
  [account-service]="nexus-account-service:Dockerfile"
  [transaction-service]="nexus-transaction-service:Dockerfile"
  [fraud-service]="nexus-fraud-service:Dockerfile"
  [ledger-service]="nexus-ledger-service:Dockerfile"
  [saga-orchestrator]="nexus-saga-orchestrator:Dockerfile"
  [ai-assistant-service]="nexus-ai-assistant-service:Dockerfile"
  [ai-kyc-service]="nexus-ai-kyc-service:Dockerfile"
  [analytics-service]="nexus-analytics-service:Dockerfile"
  [notification-service]="nexus-notification-service:Dockerfile"
  [risk-scoring-service]="nexus-risk-scoring-service:Dockerfile"
  [audit-query-jvm]="nexus-audit-query-jvm:Dockerfile"
  [audit-write-native]="audit-write-native:Dockerfile.native"
  [api-gateway]="nexus-api-gateway:Dockerfile"
)

SELECTED=("${!SERVICES[@]}")
if [ "$#" -gt 0 ]; then
  SELECTED=()
  for name in "${!SERVICES[@]}"; do
    for frag in "$@"; do
      [[ "$name" == *"$frag"* ]] && SELECTED+=("$name")
    done
  done
fi

echo "Logging in to $ECR_REGISTRY..."
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$ECR_REGISTRY"

for name in "${SELECTED[@]}"; do
  entry="${SERVICES[$name]}"
  context="${entry%%:*}"
  dockerfile="${entry##*:}"
  repo="nexus/${name}"
  full_image="${ECR_REGISTRY}/${repo}"

  echo ""
  echo "── ${name} ──────────────────────────────────────"

  # Create the ECR repo if it doesn't exist yet — idempotent, safe to
  # re-run. Real production would provision this via Terraform instead;
  # fine as a manual step while iterating solo.
  aws ecr describe-repositories --repository-names "$repo" --region "$AWS_REGION" \
    >/dev/null 2>&1 || \
    aws ecr create-repository --repository-name "$repo" --region "$AWS_REGION" >/dev/null

  docker build -t "${full_image}:${TAG}" -f "${context}/${dockerfile}" "${context}"
  docker push "${full_image}:${TAG}"

  if [ -n "${VERSION:-}" ]; then
    docker tag "${full_image}:${TAG}" "${full_image}:${VERSION}"
    docker push "${full_image}:${VERSION}"
  fi

  echo "Pushed ${full_image}:${TAG}$( [ -n "${VERSION:-}" ] && echo " and :${VERSION}" )"
done

echo ""
echo "Done. Rollback = re-deploy the previous <git-short-sha> tag for the"
echo "affected service; \`git log --oneline\` maps each tag back to the"
echo "exact commit that produced it."
