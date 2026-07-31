# terraform/backend-bootstrap — bootstraps the S3 bucket used as the remote
# state backend for terraform/ (environments/{dev,prod} once that split
# exists - see 05_TERRAFORM_AWS_CHANGES.md Section 2).
#
# This is a SEPARATE, standalone Terraform root module with its own LOCAL
# state - it must never be migrated to the S3 backend it creates (that
# would be trying to store the bucket's own state inside itself before it
# exists). Run this once, manually, reviewing `terraform plan` first:
#
#   cd terraform/backend-bootstrap
#   terraform init
#   terraform plan
#   terraform apply
#
# Not run in this session - AWS CLI/Terraform commands against real
# infrastructure are for you to execute, reviewing the plan each time.

terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "us-east-1"
}

data "aws_caller_identity" "current" {}

# ── KMS key dedicated to state encryption ───────────────────────────────
# Not reused from aws_kms_key.fraud_alerts (lambda-fraud-alert.tf) - a
# state file covering 160+ resources across the whole platform deserves
# its own key, not shared blast radius with one DynamoDB table's key.

resource "aws_kms_key" "terraform_state" {
  description         = "KMS key for Terraform remote state encryption"
  enable_key_rotation = true

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_kms_alias" "terraform_state" {
  name          = "alias/nexus-josue-terraform-state"
  target_key_id = aws_kms_key.terraform_state.key_id
}

# ── S3 bucket ────────────────────────────────────────────────────────────

resource "aws_s3_bucket" "terraform_state" {
  # Bucket names are globally unique across all of S3 - the account ID
  # suffix avoids collision with any other AWS account on the planet.
  bucket = "nexus-josue-terraform-state-${data.aws_caller_identity.current.account_id}"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  versioning_configuration {
    status = "Enabled" # recover a corrupted/overwritten state from human error
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.terraform_state.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket                  = aws_s3_bucket.terraform_state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "bucket_name" {
  value = aws_s3_bucket.terraform_state.bucket
}

output "kms_key_arn" {
  value = aws_kms_key.terraform_state.arn
}
