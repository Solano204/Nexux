# ── Plane Bridge Secret ──────────────────────────────────────────────────────
# Shared secret used by Lambdas (Plane B, real AWS) to authenticate HTTP calls
# back into the local Docker Compose stack (Plane A):
#   - nexus-auth-lambda          → GET  /internal/v1/users/{userId}/kyc/status
#   - nexus-fraud-alert-lambda   → notifies Plane A of fraud decisions
#   - nexus-health-monitor-lambda→ checks /actuator/health on every service
#   - nexus-payment-processor-lambda → HTTP bridge mode to API Gateway
#
# Lambdas send it as the `X-Plane-Bridge-Secret` header. Real state per
# integration, audited in CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md:
#   - nexus-payment-processor-lambda → nexus-transaction-service: validated
#     (InternalTransactionController.bridgePublish), already correct before
#     this file's comment was last updated.
#   - nexus-auth-lambda → nexus-identity-service: validated
#     (InternalController.requirePlaneBridgeSecret). Same secret value also
#     needs to go into nexus-identity-service's ./secrets/plane_bridge_secret.txt.
#   - nexus-health-monitor-lambda → every service's /actuator/health: not
#     validated, and shouldn't be — that endpoint is intentionally public
#     everywhere (Docker's own HEALTHCHECK hits it without this header).
#   - nexus-fraud-alert-lambda → no HTTP call back into Plane A exists in
#     its code at all; this line describes an integration that was never
#     built, not a validation gap.
# ─────────────────────────────────────────────────────────────────────────────

resource "random_password" "plane_bridge_secret" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "plane_bridge" {
  name        = "nexus-josue/plane-bridge-secret"
  description = "Shared secret for Lambda -> docker-compose local-plane HTTP bridge calls"
}

resource "aws_secretsmanager_secret_version" "plane_bridge" {
  secret_id     = aws_secretsmanager_secret.plane_bridge.id
  secret_string = jsonencode({ secret = random_password.plane_bridge_secret.result })
}

# Convenience local — every Lambda env block does:
#   PLANE_BRIDGE_SECRET = local.plane_bridge_secret_value
locals {
  plane_bridge_secret_value = jsondecode(aws_secretsmanager_secret_version.plane_bridge.secret_string)["secret"]
}
