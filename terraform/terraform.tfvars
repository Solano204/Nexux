# ── Production values ─────────────────────────────────────────────────────────
# This file is gitignored if it contains secrets.
# For CI/CD, inject these as TF_VAR_* environment variables instead.
# ─────────────────────────────────────────────────────────────────────────────

aws_region   = "us-east-1"
environment  = "prod"

kyc_bucket_name         = "nexus-kyc-documents"
kyc_queue_name          = "nexus-kyc-documents-pending"
kyc_dlq_name            = "nexus-kyc-documents-pending-dlq"
document_retention_days = 2555  # 7 years

iam_user_name = "nexus-platform-svc"

cors_allowed_origins = ["https://app.nexusbank.com"]
