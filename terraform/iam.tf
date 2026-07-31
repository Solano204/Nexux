# ── IAM User ──────────────────────────────────────────────────────────────────
# One long-lived IAM user for all Nexus platform services.
# Its access key is injected as AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY.
#
# Which services use this user:
#   nexus-identity-service        → S3 PutObject, SQS SendMessage,
#                                    Cognito AdminCreateUser/SetUserPassword/
#                                    UpdateUserAttributes (Option B mirror)
#   nexus-ai-kyc-service          → S3 GetObject, SQS ReceiveMessage/DeleteMessage
#   nexus-fraud-service           → SQS SendMessage (fraud alerts)
#   nexus-notification-service    → SNS Publish (notification dispatch)
#   nexus-transaction-service     → DynamoDB PutItem (analytics snapshot feed)
#
# All other services (account, ledger, etc.) only use:
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
  name        = "nexus-josue-s3-kyc-policy-${var.environment}"
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

# ── SQS Policy (KYC) ──────────────────────────────────────────────────────────
# nexus-identity-service: SendMessage         (produces KYC jobs)
# nexus-ai-kyc-service:   ReceiveMessage +    (consumes KYC jobs)
#                         DeleteMessage +
#                         ChangeMessageVisibility

resource "aws_iam_policy" "nexus_sqs_kyc" {
  name        = "nexus-josue-sqs-kyc-policy-${var.environment}"
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

# ── SQS Policy (Fraud Alerts) ─────────────────────────────────────────────────
# nexus-fraud-service: SendMessage (produces high-severity fraud alerts consumed
# by nexus-fraud-alert-lambda)

resource "aws_iam_policy" "nexus_sqs_fraud" {
  name        = "nexus-josue-sqs-fraud-policy-${var.environment}"
  description = "SQS fraud alert queue write access for nexus-fraud-service"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "FraudAlertQueueSend"
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl"
        ]
        Resource = aws_sqs_queue.fraud_alert_high_severity.arn
      }
    ]
  })
}

# ── SQS Policy (KYC Rekognition Results) ──────────────────────────────────────
# nexus-ai-kyc-service: consumes Rekognition processing results produced by
# nexus-kyc-rekognition-lambda

resource "aws_iam_policy" "nexus_sqs_kyc_rekognition" {
  name        = "nexus-josue-sqs-kyc-rekognition-policy-${var.environment}"
  description = "SQS rekognition results queue read access for nexus-ai-kyc-service"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "KycRekognitionResultsRead"
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = aws_sqs_queue.kyc_rekognition_results.arn
      }
    ]
  })
}

# ── SNS Policy (Notification Dispatch) ────────────────────────────────────────
# nexus-notification-service: publishes to the dispatch topic that fans out to
# nexus-notification-dispatcher-lambda

resource "aws_iam_policy" "nexus_sns_notification" {
  name        = "nexus-josue-sns-notification-policy-${var.environment}"
  description = "SNS notification dispatch topic publish access for nexus-notification-service"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "NotificationDispatchPublish"
        Effect = "Allow"
        Action = [
          "sns:Publish",
          "sns:GetTopicAttributes"
        ]
        Resource = aws_sns_topic.notification_dispatch.arn
      }
    ]
  })
}

# ── DynamoDB Policy (Transaction Analytics) ───────────────────────────────────
# nexus-transaction-service: PutItem on the transactions table (terminal-state
# snapshots) — this is the event source that feeds nexus-analytics-aggregator-lambda
# via DynamoDB Streams. See TransactionDynamoDbWriter.java.

resource "aws_iam_policy" "nexus_dynamodb_transactions" {
  name        = "nexus-josue-dynamodb-transactions-policy-${var.environment}"
  description = "DynamoDB transactions table write access for nexus-transaction-service"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "TransactionsTableWrite"
        Effect   = "Allow"
        Action   = ["dynamodb:PutItem"]
        Resource = aws_dynamodb_table.transactions.arn
      }
    ]
  })
}

# ── Cognito Policy (Option B auth mirror) ─────────────────────────────────────
# nexus-identity-service: mirrors Docker-plane users into the Cognito user pool
# on registration + KYC status changes, and revokes the Cognito session on
# logout (CognitoUserMirror) — see AWS-DOCKER-WORKFLOWS/02_LOGIN_FLOW.md
#
# Login itself (InitiateAuth + USER_PASSWORD_AUTH) needs no IAM permission
# here — it's one of Cognito's unauthenticated-by-design APIs, gated by the
# app client's explicit_auth_flows instead (see lambda-auth.tf).

resource "aws_iam_policy" "nexus_cognito_mirror" {
  name        = "nexus-josue-cognito-mirror-policy-${var.environment}"
  description = "Cognito admin user-mirroring access for nexus-identity-service"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "CognitoUserMirror"
        Effect = "Allow"
        Action = [
          "cognito-idp:AdminCreateUser",
          "cognito-idp:AdminSetUserPassword",
          "cognito-idp:AdminUpdateUserAttributes",
          "cognito-idp:AdminGetUser",
          "cognito-idp:AdminUserGlobalSignOut"
        ]
        Resource = aws_cognito_user_pool.nexus.arn
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

resource "aws_iam_user_policy_attachment" "nexus_sqs_fraud" {
  user       = aws_iam_user.nexus_platform.name
  policy_arn = aws_iam_policy.nexus_sqs_fraud.arn
}

resource "aws_iam_user_policy_attachment" "nexus_sqs_kyc_rekognition" {
  user       = aws_iam_user.nexus_platform.name
  policy_arn = aws_iam_policy.nexus_sqs_kyc_rekognition.arn
}

resource "aws_iam_user_policy_attachment" "nexus_sns_notification" {
  user       = aws_iam_user.nexus_platform.name
  policy_arn = aws_iam_policy.nexus_sns_notification.arn
}

resource "aws_iam_user_policy_attachment" "nexus_cognito_mirror" {
  user       = aws_iam_user.nexus_platform.name
  policy_arn = aws_iam_policy.nexus_cognito_mirror.arn
}

resource "aws_iam_user_policy_attachment" "nexus_dynamodb_transactions" {
  user       = aws_iam_user.nexus_platform.name
  policy_arn = aws_iam_policy.nexus_dynamodb_transactions.arn
}
