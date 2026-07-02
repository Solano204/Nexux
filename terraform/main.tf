terraform {
  required_version = ">= 1.6"

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

  # ── Remote state backend (uncomment once you create the bucket manually) ──
  # This stores terraform.tfstate in S3 so it's not lost and can be shared.
  # Steps:
  #   1. Create the bucket manually in AWS console (one-time bootstrap)
  #   2. Uncomment the block below
  #   3. Run: terraform init -migrate-state
  #
  # backend "s3" {
  #   bucket         = "nexus-josue-terraform-state-<your-account-id>"
  #   key            = "nexus-josue-platform/terraform.tfstate"
  #   region         = "us-east-1"
  #   encrypt        = true
  #   dynamodb_table = "nexus-josue-terraform-lock"   # optional but recommended
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
