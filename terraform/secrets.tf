# ── Plane Bridge Secret ──────────────────────────────────────────────────────
# Shared secret used by Lambdas (Plane B, real AWS) to authenticate HTTP calls
# back into the local Docker Compose stack (Plane A):
#   - nexus-auth-lambda          → GET  /internal/v1/users/{userId}/kyc/status
#   - nexus-fraud-alert-lambda   → notifies Plane A of fraud decisions
#   - nexus-health-monitor-lambda→ checks /actuator/health on every service
#   - nexus-payment-processor-lambda → HTTP bridge mode to API Gateway
#
# Lambdas send it as the `X-Plane-Bridge-Secret` header. The receiving
# Plane A service must validate that header against this same value —
# none of the current docker-compose-prod.yml services do this yet, so this
# is the value to wire into whichever service ends up validating the header.
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
