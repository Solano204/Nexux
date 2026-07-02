# ── Account / region lookups ────────────────────────────────────────────────
# Used across lambda-*.tf files to build IAM resource ARNs that reference
# resources defined in a *different* file without creating a hard Terraform
# dependency between them (mirrors how the original per-Lambda SAM templates
# were independent stacks that only agreed on resource names).
# ─────────────────────────────────────────────────────────────────────────────

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}
