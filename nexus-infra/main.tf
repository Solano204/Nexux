terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # ── Remote state backend (uncomment once you create the bucket manually) ──
  # This stores terraform.tfstate in S3 so it's not lost and can be shared.
  # Steps:
  #   1. Create the bucket manually in AWS console (one-time bootstrap)
  #   2. Uncomment the block below
  #   3. Run: terraform init -migrate-state
  #
  # backend "s3" {
  #   bucket         = "nexus-terraform-state-<your-account-id>"
  #   key            = "nexus-platform/terraform.tfstate"
  #   region         = "us-east-1"
  #   encrypt        = true
  #   dynamodb_table = "nexus-terraform-lock"   # optional but recommended
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "nexus-platform"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
