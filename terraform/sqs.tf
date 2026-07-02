# ── KYC Dead-Letter Queue ─────────────────────────────────────────────────────
resource "aws_sqs_queue" "kyc_dlq" {
  name                      = var.kyc_dlq_name
  message_retention_seconds = 1209600
}

# ── KYC Processing Queue ──────────────────────────────────────────────────────
resource "aws_sqs_queue" "kyc_pending" {
  name                       = var.kyc_queue_name
  visibility_timeout_seconds = 300
  message_retention_seconds  = 86400
  receive_wait_time_seconds  = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.kyc_dlq.arn
    maxReceiveCount     = 3
  })
}

resource "aws_sqs_queue_policy" "kyc_pending" {
  queue_url = aws_sqs_queue.kyc_pending.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowNexusPlatformUser"
        Effect = "Allow"
        Principal = {
          AWS = aws_iam_user.nexus_platform.arn
        }
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = aws_sqs_queue.kyc_pending.arn
      }
    ]
  })
}
