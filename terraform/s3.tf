# ── KYC Document Bucket ───────────────────────────────────────────────────────
# Consumers:
#   nexus-identity-service  → PutObject / PutObjectTagging  (KYC initiate)
#   nexus-ai-kyc-service    → GetObject / GetObjectTagging  (AI pipeline)
# ─────────────────────────────────────────────────────────────────────────────

resource "aws_s3_bucket" "kyc_documents" {
  bucket = var.kyc_bucket_name

  lifecycle {
    # KYC documents carry a 7-year regulatory retention requirement
    # (document_retention_days = 2555) - accidental destroy here is
    # evidence loss, potentially a compliance violation.
    prevent_destroy = true
  }
}

# Block all public access — KYC documents must never be publicly accessible
resource "aws_s3_bucket_public_access_block" "kyc_documents" {
  bucket = aws_s3_bucket.kyc_documents.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Server-side encryption with AES-256
resource "aws_s3_bucket_server_side_encryption_configuration" "kyc_documents" {
  bucket = aws_s3_bucket.kyc_documents.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

# Versioning — keeps previous document versions for audit trail
resource "aws_s3_bucket_versioning" "kyc_documents" {
  bucket = aws_s3_bucket.kyc_documents.id

  versioning_configuration {
    status = "Enabled"
  }
}

# Lifecycle: move to Glacier after 90 days; hard-delete after retention period
resource "aws_s3_bucket_lifecycle_configuration" "kyc_documents" {
  bucket = aws_s3_bucket.kyc_documents.id

  # Versioning must exist before lifecycle rules can reference noncurrent versions
  depends_on = [aws_s3_bucket_versioning.kyc_documents]

  rule {
    id     = "kyc-document-retention"
    status = "Enabled"

    filter {
      prefix = "kyc/"
    }

    transition {
      days          = 90
      storage_class = "GLACIER"
    }

    expiration {
      days = var.document_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

# CORS for presigned-URL direct browser uploads
resource "aws_s3_bucket_cors_configuration" "kyc_documents" {
  bucket = aws_s3_bucket.kyc_documents.id

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["PUT", "POST", "GET"]
    allowed_origins = var.cors_allowed_origins
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

# ── Bucket policy: deny unencrypted uploads ───────────────────────────────────
# Extra hardening: even if the IAM policy allows PutObject, the object-level
# request must use HTTPS and will inherit bucket-level AES-256.
resource "aws_s3_bucket_policy" "kyc_documents_deny_http" {
  bucket = aws_s3_bucket.kyc_documents.id

  # Must wait for public-access-block to be applied first
  depends_on = [aws_s3_bucket_public_access_block.kyc_documents]

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyHTTP"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.kyc_documents.arn,
          "${aws_s3_bucket.kyc_documents.arn}/*"
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      }
    ]
  })
}
