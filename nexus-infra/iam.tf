# ── IAM User ──────────────────────────────────────────────────────────────────
# One long-lived IAM user for all Nexus platform services.
# Its access key is injected as AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY.
#
# Which services use this user:
#   nexus-identity-service   → S3 PutObject, SQS SendMessage
#   nexus-ai-kyc-service     → S3 GetObject, SQS ReceiveMessage/DeleteMessage
#
# All other services (account, fraud, transaction, ledger, etc.) only use:
#   - Kafka (self-hosted)         → no AWS credential needed
#   - PostgreSQL (self-hosted)    → no AWS credential needed
#   - MongoDB (self-hosted)       → no AWS credential needed
#   - Elasticsearch (self-hosted) → no AWS credential needed
#   - OpenAI API                  → OPENAI_API_KEY, not AWS IAM
# ─────────────────────────────────────────────────────────────────────────────

resource "aws_iam_user" "nexus_platform" {
  name = var.iam_user_name
  path = "/nexus/"
}

resource "aws_iam_access_key" "nexus_platform" {
  user = aws_iam_user.nexus_platform.name
}

# ── S3 Policy ─────────────────────────────────────────────────────────────────
# nexus-identity-service: PutObject + PutObjectTagging (upload document)
# nexus-ai-kyc-service:   GetObject + GetObjectTagging (read for AI processing)
# Both:                   ListBucket (for existence checks / SDK internal calls)

resource "aws_iam_policy" "nexus_s3_kyc" {
  name        = "nexus-s3-kyc-policy-${var.environment}"
  description = "S3 KYC document bucket access for nexus-identity and nexus-ai-kyc services"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "KycObjectReadWrite"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:PutObjectTagging",
          "s3:GetObject",
          "s3:GetObjectTagging",
          "s3:DeleteObject"
        ]
        # Scoped to kyc/ prefix — never grants access to other prefixes
        Resource = "${aws_s3_bucket.kyc_documents.arn}/kyc/*"
      },
      {
        Sid    = "KycBucketInspect"
        Effect = "Allow"
        Action = [
          "s3:ListBucket",
          "s3:GetBucketLocation"
        ]
        Resource = aws_s3_bucket.kyc_documents.arn
      }
    ]
  })
}

# ── SQS Policy ────────────────────────────────────────────────────────────────
# nexus-identity-service: SendMessage         (produces KYC jobs)
# nexus-ai-kyc-service:   ReceiveMessage +    (consumes KYC jobs)
#                         DeleteMessage +
#                         ChangeMessageVisibility

resource "aws_iam_policy" "nexus_sqs_kyc" {
  name        = "nexus-sqs-kyc-policy-${var.environment}"
  description = "SQS KYC queue access for nexus-identity and nexus-ai-kyc services"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "KycQueueAccess"
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = aws_sqs_queue.kyc_pending.arn
      },
      {
        Sid    = "KycDlqRead"
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes"
        ]
        Resource = aws_sqs_queue.kyc_dlq.arn
      }
    ]
  })
}

# ── Attach policies to user ───────────────────────────────────────────────────

resource "aws_iam_user_policy_attachment" "nexus_s3" {
  user       = aws_iam_user.nexus_platform.name
  policy_arn = aws_iam_policy.nexus_s3_kyc.arn
}

resource "aws_iam_user_policy_attachment" "nexus_sqs" {
  user       = aws_iam_user.nexus_platform.name
  policy_arn = aws_iam_policy.nexus_sqs_kyc.arn
}
