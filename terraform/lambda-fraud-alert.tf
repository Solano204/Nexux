# ── Nexus Fraud Alert Lambda ─────────────────────────────────────────────────
# Port of LAMBDA/nexus-fraud-alert-lambda/nexus-fraud-alert-lambda/template.yaml
#
# Input: nexus-fraud-service sends fraud events via direct SQS SendMessage
# (its AwsConfig.java only wires an SqsClient bean — no SNS). There is no
# inbound SNS topic in front of this Lambda. The two SNS topics below are
# OUTPUTS the Lambda publishes to (compliance / security-ops alerting).
# ─────────────────────────────────────────────────────────────────────────────

variable "compliance_team_email" {
  description = "Email address for compliance team fraud alerts"
  type        = string
}

variable "local_plane_notification_url" {
  description = <<-EOT
    Plane A API Gateway URL for user-facing notifications.
    http://host.docker.internal:8080 only resolves when running `sam local` —
    a real AWS Lambda cannot reach a machine behind NAT. Override with a
    publicly reachable tunnel (ngrok / Cloudflare Tunnel / Tailscale Funnel)
    or your real API Gateway domain once you deploy for real.
  EOT
  type        = string
  default     = "http://host.docker.internal:8080"
}

variable "fraud_alert_lambda_jar_path" {
  description = "Path to the built shaded jar. Built automatically by null_resource.fraud_alert_lambda_build on apply."
  type        = string
  default     = "../LAMBDA/nexus-fraud-alert-lambda/nexus-fraud-alert-lambda/target/nexus-fraud-alert-lambda-1.0.0.jar"
}

resource "null_resource" "fraud_alert_lambda_build" {
  triggers = {
    pom_hash = filesha256("../LAMBDA/nexus-fraud-alert-lambda/nexus-fraud-alert-lambda/pom.xml")
    source_hash = sha1(join("", [
      for f in sort(fileset("../LAMBDA/nexus-fraud-alert-lambda/nexus-fraud-alert-lambda/src/main", "**")) :
      filesha1("../LAMBDA/nexus-fraud-alert-lambda/nexus-fraud-alert-lambda/src/main/${f}")
    ]))
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = "JAVA_HOME='/c/Program Files/Java/jdk-21' mvn -f ../LAMBDA/nexus-fraud-alert-lambda/nexus-fraud-alert-lambda/pom.xml clean package -DskipTests -q"
  }
}

data "local_file" "fraud_alert_lambda_jar" {
  filename   = var.fraud_alert_lambda_jar_path
  depends_on = [null_resource.fraud_alert_lambda_build]
}

# ── SQS Queues ────────────────────────────────────────────────────────────────

resource "aws_sqs_queue" "fraud_alert_high_severity" {
  name                       = "nexus-josue-fraud-alerts-high-severity"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 86400
  receive_wait_time_seconds  = 5

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.fraud_alert_dlq.arn
    maxReceiveCount     = 2
  })
}

resource "aws_sqs_queue" "fraud_alert_dlq" {
  name                      = "nexus-josue-fraud-alerts-dlq"
  message_retention_seconds = 604800
}

# ── KMS Key ───────────────────────────────────────────────────────────────────

resource "aws_kms_key" "fraud_alerts" {
  description         = "KMS key for nexus-fraud-alerts DynamoDB table"
  enable_key_rotation = true
}

# ── DynamoDB Tables ───────────────────────────────────────────────────────────

resource "aws_dynamodb_table" "fraud_alerts" {
  name         = "nexus-josue-fraud-alerts"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

  attribute {
    name = "PK"
    type = "S"
  }
  attribute {
    name = "SK"
    type = "S"
  }
  attribute {
    name = "GSI_USER_PK"
    type = "S"
  }
  attribute {
    name = "GSI_USER_SK"
    type = "S"
  }
  attribute {
    name = "GSI_DATE_PK"
    type = "S"
  }
  attribute {
    name = "GSI_DATE_SK"
    type = "S"
  }
  attribute {
    name = "GSI_STATUS_PK"
    type = "S"
  }
  attribute {
    name = "GSI_STATUS_SK"
    type = "S"
  }

  global_secondary_index {
    name            = "UserIndex"
    hash_key        = "GSI_USER_PK"
    range_key       = "GSI_USER_SK"
    projection_type = "ALL"
  }
  global_secondary_index {
    name            = "DateIndex"
    hash_key        = "GSI_DATE_PK"
    range_key       = "GSI_DATE_SK"
    projection_type = "ALL"
  }
  global_secondary_index {
    name            = "StatusIndex"
    hash_key        = "GSI_STATUS_PK"
    range_key       = "GSI_STATUS_SK"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.fraud_alerts.arn
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }
}

resource "aws_dynamodb_table" "sar_considerations" {
  name         = "nexus-josue-sar-considerations"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

  attribute {
    name = "PK"
    type = "S"
  }
  attribute {
    name = "SK"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }
}

# ── SNS Topics ────────────────────────────────────────────────────────────────

resource "aws_sns_topic" "fraud_compliance_alerts" {
  name = "nexus-josue-fraud-compliance-alerts"
}

resource "aws_sns_topic_subscription" "fraud_compliance_email" {
  topic_arn = aws_sns_topic.fraud_compliance_alerts.arn
  protocol  = "email"
  endpoint  = var.compliance_team_email
}

resource "aws_sns_topic" "security_operations" {
  name = "nexus-josue-security-operations"
}

# ── IAM Role ──────────────────────────────────────────────────────────────────

resource "aws_iam_role" "fraud_alert_lambda" {
  name = "nexus-josue-fraud-alert-lambda-role-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "lambda.amazonaws.com" }
        Action    = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "fraud_alert_lambda_basic" {
  role       = aws_iam_role.fraud_alert_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "fraud_alert_lambda_xray" {
  role       = aws_iam_role.fraud_alert_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_iam_role_policy" "fraud_alert_lambda" {
  name = "nexus-josue-fraud-alert-lambda-policy-${var.environment}"
  role = aws_iam_role.fraud_alert_lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "FraudAlertsTableCrud"
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query",
          "dynamodb:Scan",
          "dynamodb:BatchGetItem",
          "dynamodb:BatchWriteItem",
          "dynamodb:ConditionCheckItem"
        ]
        Resource = [
          aws_dynamodb_table.fraud_alerts.arn,
          "${aws_dynamodb_table.fraud_alerts.arn}/index/*",
          aws_dynamodb_table.sar_considerations.arn,
          "${aws_dynamodb_table.sar_considerations.arn}/index/*"
        ]
      },
      {
        Sid    = "FraudAlertsKmsUsage"
        Effect = "Allow"
        Action = [
          "kms:Encrypt",
          "kms:Decrypt",
          "kms:GenerateDataKey"
        ]
        Resource = aws_kms_key.fraud_alerts.arn
      },
      {
        Sid    = "FraudAlertSnsPublish"
        Effect = "Allow"
        Action = "sns:Publish"
        Resource = [
          aws_sns_topic.fraud_compliance_alerts.arn,
          aws_sns_topic.security_operations.arn
        ]
      },
      {
        Sid    = "FraudAlertSqsConsume"
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = aws_sqs_queue.fraud_alert_high_severity.arn
      },
      {
        Sid      = "FraudAlertMetrics"
        Effect   = "Allow"
        Action   = "cloudwatch:PutMetricData"
        Resource = "*"
      },
      {
        Sid      = "FraudAlertPlaneBridgeSecret"
        Effect   = "Allow"
        Action   = "secretsmanager:GetSecretValue"
        Resource = "arn:aws:secretsmanager:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:secret:nexus/*"
      }
    ]
  })
}

# ── Lambda Function ───────────────────────────────────────────────────────────

resource "aws_lambda_function" "fraud_alert" {
  function_name = "nexus-josue-fraud-alert-lambda"
  role          = aws_iam_role.fraud_alert_lambda.arn
  handler       = "com.nexus.fraud.lambda.FraudAlertHandler::handleRequest"
  runtime       = "java21"

  filename         = data.local_file.fraud_alert_lambda_jar.filename
  source_code_hash = data.local_file.fraud_alert_lambda_jar.content_base64sha256

  memory_size = 512
  timeout     = 30
  publish     = true

  # No reserved concurrency for now — this AWS account's total Lambda
  # concurrency quota is only 10 (AccountLimit.ConcurrentExecutions), and AWS
  # requires >=10 unreserved after any reservation. Once you request a quota
  # increase from AWS Support, re-add: reserved_concurrent_executions = 20

  tracing_config {
    mode = "Active"
  }

  # SnapStart: fraud attacks are bursty. Without it, the first alerts in a
  # burst take 5-15s to process; with it, ~300ms.
  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      AWS_REGION_NAME              = data.aws_region.current.name
      FRAUD_ALERTS_TABLE           = aws_dynamodb_table.fraud_alerts.name
      SAR_CONSIDERATIONS_TABLE     = aws_dynamodb_table.sar_considerations.name
      COMPLIANCE_SNS_TOPIC_ARN     = aws_sns_topic.fraud_compliance_alerts.arn
      OPERATIONS_SNS_TOPIC_ARN     = aws_sns_topic.security_operations.arn
      CLOUDWATCH_NAMESPACE         = "Nexus/FraudAlerts"
      SAR_RISK_SCORE_THRESHOLD     = "90"
      SAR_PATTERNS                 = "STRUCTURING,LAYERING,ACCOUNT_TAKEOVER,AML"
      ENVIRONMENT                  = var.environment
      LOCAL_PLANE_NOTIFICATION_URL = var.local_plane_notification_url
      PLANE_BRIDGE_SECRET          = local.plane_bridge_secret_value
    }
  }

  depends_on = [
    aws_iam_role_policy.fraud_alert_lambda,
    aws_iam_role_policy_attachment.fraud_alert_lambda_basic
  ]
}

resource "aws_lambda_alias" "fraud_alert_live" {
  name             = "live"
  function_name    = aws_lambda_function.fraud_alert.function_name
  function_version = aws_lambda_function.fraud_alert.version
}

# ── SQS Trigger ───────────────────────────────────────────────────────────────

resource "aws_lambda_event_source_mapping" "fraud_alert_sqs" {
  event_source_arn = aws_sqs_queue.fraud_alert_high_severity.arn
  function_name    = aws_lambda_alias.fraud_alert_live.arn

  batch_size                         = 5
  maximum_batching_window_in_seconds = 2

  function_response_types = ["ReportBatchItemFailures"]
}

# ── CloudWatch Alarms ─────────────────────────────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "fraud_alert_processing_errors" {
  alarm_name          = "nexus-josue-fraud-alert-processing-errors"
  alarm_description   = "Fraud alert Lambda errors. Any error = potential missed compliance notification. Page on-call immediately."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.fraud_alert.function_name }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "fraud_alert_dlq_depth" {
  alarm_name          = "nexus-josue-fraud-alert-dlq-messages"
  alarm_description   = "Fraud alerts in DLQ after 2 processing failures. High-severity events that failed compliance notification. IMMEDIATE investigation required."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.fraud_alert_dlq.name }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
}

resource "aws_cloudwatch_metric_alarm" "high_severity_fraud_spike" {
  alarm_name          = "nexus-josue-fraud-high-severity-spike"
  alarm_description   = "10+ high-severity fraud alerts in 5 minutes. Indicates active coordinated attack campaign. Notify security operations center immediately."
  namespace           = "Nexus/FraudAlerts"
  metric_name         = "fraud.high.severity.count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanOrEqualToThreshold"
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "fraud_alert_queue_url" {
  description = "SQS queue URL nexus-fraud-service must send high-severity fraud events to"
  value       = aws_sqs_queue.fraud_alert_high_severity.url
}

output "fraud_alert_queue_arn" {
  description = "Maps conceptually to docker-compose's SNS_FRAUD_ALERTS_TOPIC_ARN env var name, though it's actually an SQS queue ARN — that env var isn't wired into fraud-service's AwsConfig.java yet (SqsClient only, no SNS)"
  value       = aws_sqs_queue.fraud_alert_high_severity.arn
}

output "fraud_alerts_table_name" {
  value = aws_dynamodb_table.fraud_alerts.name
}

output "compliance_topic_arn" {
  value = aws_sns_topic.fraud_compliance_alerts.arn
}

output "security_operations_topic_arn" {
  value = aws_sns_topic.security_operations.arn
}

output "fraud_alert_lambda_arn" {
  value = aws_lambda_function.fraud_alert.arn
}
