# ── General ───────────────────────────────────────────────────────────────────

variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment tag (prod | staging | dev)"
  type        = string
  default     = "prod"

  validation {
    condition     = contains(["prod", "staging", "dev"], var.environment)
    error_message = "environment must be one of: prod, staging, dev"
  }
}

# ── S3 ────────────────────────────────────────────────────────────────────────

variable "kyc_bucket_name" {
  description = "S3 bucket for KYC documents. nexus-identity-service uploads; nexus-ai-kyc-service reads."
  type        = string
  default     = "nexus-josue-kyc-documents"
}

variable "document_retention_days" {
  description = "Days before KYC documents expire (7 years = 2555 days — regulatory requirement)"
  type        = number
  default     = 2555

  validation {
    condition     = var.document_retention_days >= 365
    error_message = "Retention must be at least 365 days for regulatory compliance."
  }
}

variable "cors_allowed_origins" {
  description = "Origins allowed for S3 presigned-URL direct uploads. Restrict to your domain in production."
  type        = list(string)
  default     = ["https://app.nexusbank.com"]
  # Dev override in terraform.tfvars: ["http://localhost:3000", "http://localhost:5173"]
}

# ── SQS ───────────────────────────────────────────────────────────────────────

variable "kyc_queue_name" {
  description = "SQS queue consumed by nexus-ai-kyc-service. Must match KYC_QUEUE_URL / KYC_SQS_QUEUE_URL."
  type        = string
  default     = "nexus-josue-kyc-documents-pending"
}

variable "kyc_dlq_name" {
  description = "Dead-letter queue for messages that fail 3 processing attempts"
  type        = string
  default     = "nexus-josue-kyc-documents-pending-dlq"
}

# ── IAM ───────────────────────────────────────────────────────────────────────

variable "iam_user_name" {
  description = "IAM user whose access key is used by all Nexus services via AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY"
  type        = string
  default     = "nexus-josue-platform-svc"
}
