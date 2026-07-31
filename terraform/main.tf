terraform {
  # >= 1.11 for native S3 locking (use_lockfile, below) - was >= 1.6, which
  # predates that feature by a wide margin. See
  # CHANGES-BESTPRACTICES/05_TERRAFORM_AWS_CHANGES.md Section 1.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.2"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.5"
    }
  }

  # ── Remote state backend ────────────────────────────────────────────────
  # Left commented on purpose - uncommenting this without first running
  # terraform/backend-bootstrap (creates the bucket + KMS key) makes every
  # `terraform init` here fail immediately (the backend doesn't exist yet).
  # Steps once you're ready:
  #   1. cd terraform/backend-bootstrap && terraform init && terraform plan
  #      && terraform apply (review the plan first, as always)
  #   2. Uncomment the block below, filling in the real account ID
  #   3. Run: terraform init -migrate-state (from terraform/, not
  #      backend-bootstrap/) - it will ask to copy the existing local state
  #      into the new backend; confirm yes
  #   4. Verify `terraform state list` shows the same resource count as
  #      before migrating (160 resources as of this audit) before trusting
  #      the migration
  #
  # backend "s3" {
  #   bucket       = "nexus-josue-terraform-state-<your-account-id>"
  #   key          = "nexus-josue-platform/terraform.tfstate"
  #   region       = "us-east-1"
  #   encrypt      = true
  #   use_lockfile = true   # native locking via S3 conditional writes (1.11+)
  #                         # - intentionally NO dynamodb_table, avoiding
  #                         # that extra piece of infrastructure from day one
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "nexus-josue-platform"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
