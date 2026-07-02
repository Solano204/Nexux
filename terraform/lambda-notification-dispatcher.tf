# ── Nexus Notification Dispatcher Lambda ─────────────────────────────────────
# Port of LAMBDA/nexus-notification-dispatcher-lambda/nexus-notification-dispatcher-lambda/template.yaml
#
# Input: nexus-notification-service publishes to notification_dispatch (SNS),
# which fans out to this Lambda AND to notification_audit (SQS, for
# compliance replay of security-relevant events).
# ─────────────────────────────────────────────────────────────────────────────

variable "notification_dispatcher_lambda_jar_path" {
  description = "Path to the built shaded jar. Built automatically by null_resource.notification_dispatcher_lambda_build on apply."
  type        = string
  default     = "../LAMBDA/nexus-notification-dispatcher-lambda/nexus-notification-dispatcher-lambda/target/nexus-notification-dispatcher-lambda-1.0.0.jar"
}

resource "null_resource" "notification_dispatcher_lambda_build" {
  triggers = {
    pom_hash = filesha256("../LAMBDA/nexus-notification-dispatcher-lambda/nexus-notification-dispatcher-lambda/pom.xml")
    source_hash = sha1(join("", [
      for f in sort(fileset("../LAMBDA/nexus-notification-dispatcher-lambda/nexus-notification-dispatcher-lambda/src/main", "**")) :
      filesha1("../LAMBDA/nexus-notification-dispatcher-lambda/nexus-notification-dispatcher-lambda/src/main/${f}")
    ]))
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = "JAVA_HOME='/c/Program Files/Java/jdk-21' mvn -f ../LAMBDA/nexus-notification-dispatcher-lambda/nexus-notification-dispatcher-lambda/pom.xml clean package -DskipTests -q"
  }
}

data "local_file" "notification_dispatcher_lambda_jar" {
  filename   = var.notification_dispatcher_lambda_jar_path
  depends_on = [null_resource.notification_dispatcher_lambda_build]
}

variable "android_platform_app_arn" {
  description = "SNS platform application ARN for Android push (FCM). Null until push notifications are configured."
  type        = string
  default     = null
}

variable "ios_platform_app_arn" {
  description = "SNS platform application ARN for iOS push (APNs). Null until push notifications are configured."
  type        = string
  default     = null
}

# ── SQS Queues ────────────────────────────────────────────────────────────────

resource "aws_sqs_queue" "delivery_status" {
  name                       = "nexus-josue-delivery-status"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 3600

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.delivery_status_dlq.arn
    maxReceiveCount     = 3
  })
}

resource "aws_sqs_queue" "delivery_status_dlq" {
  name                      = "nexus-josue-delivery-status-dlq"
  message_retention_seconds = 86400
}

resource "aws_sqs_queue" "notification_audit" {
  name                       = "nexus-josue-notification-audit"
  visibility_timeout_seconds = 120
  message_retention_seconds  = 86400
}

resource "aws_sqs_queue_policy" "notification_audit" {
  queue_url = aws_sqs_queue.notification_audit.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowSnsSendMessage"
        Effect = "Allow"
        Principal = {
          Service = "sns.amazonaws.com"
        }
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.notification_audit.arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = aws_sns_topic.notification_dispatch.arn
          }
        }
      }
    ]
  })
}

# ── SNS Topic ─────────────────────────────────────────────────────────────────

resource "aws_sns_topic" "notification_dispatch" {
  name = "nexus-josue-notification-dispatch"
}

resource "aws_sns_topic_subscription" "notification_dispatch_lambda" {
  topic_arn = aws_sns_topic.notification_dispatch.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_alias.notification_dispatcher_live.arn
}

resource "aws_sns_topic_subscription" "notification_audit_sqs" {
  topic_arn = aws_sns_topic.notification_dispatch.arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.notification_audit.arn

  filter_policy = jsonencode({
    eventType = ["FRAUD_ALERT", "SECURITY_ALERT", "ACCOUNT_FROZEN", "LOGIN_NEW_DEVICE"]
  })
}

# ── SES Configuration Set ──────────────────────────────────────────────────────

resource "aws_sesv2_configuration_set" "nexus_transactional" {
  configuration_set_name = "nexus-josue-transactional"

  sending_options {
    sending_enabled = true
  }

  reputation_options {
    reputation_metrics_enabled = true
  }

  suppression_options {
    suppressed_reasons = ["BOUNCE", "COMPLAINT"]
  }
}

# ── IAM Role ──────────────────────────────────────────────────────────────────

resource "aws_iam_role" "notification_dispatcher_lambda" {
  name = "nexus-josue-notification-dispatcher-lambda-role-${var.environment}"

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

resource "aws_iam_role_policy_attachment" "notification_dispatcher_lambda_basic" {
  role       = aws_iam_role.notification_dispatcher_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "notification_dispatcher_lambda_xray" {
  role       = aws_iam_role.notification_dispatcher_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_iam_role_policy" "notification_dispatcher_lambda" {
  name = "nexus-josue-notification-dispatcher-lambda-policy-${var.environment}"
  role = aws_iam_role.notification_dispatcher_lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "NotificationSesSend"
        Effect = "Allow"
        Action = [
          "ses:SendEmail",
          "ses:SendBulkEmail"
        ]
        Resource = [
          "arn:aws:ses:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:identity/*",
          "arn:aws:ses:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:configuration-set/${aws_sesv2_configuration_set.nexus_transactional.configuration_set_name}"
        ]
      },
      {
        Sid      = "NotificationSnsPublish"
        Effect   = "Allow"
        Action   = "sns:Publish"
        Resource = "*"
      },
      {
        Sid      = "NotificationDeliveryStatusReport"
        Effect   = "Allow"
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.delivery_status.arn
      }
    ]
  })
}

# ── Lambda Function ───────────────────────────────────────────────────────────

resource "aws_lambda_function" "notification_dispatcher" {
  function_name = "nexus-josue-notification-dispatcher-lambda"
  role          = aws_iam_role.notification_dispatcher_lambda.arn
  handler       = "com.nexus.notification.lambda.NotificationDispatcherHandler::handleRequest"
  runtime       = "java21"

  filename         = data.local_file.notification_dispatcher_lambda_jar.filename
  source_code_hash = data.local_file.notification_dispatcher_lambda_jar.content_base64sha256

  memory_size = 512
  timeout     = 30
  publish     = true

  tracing_config {
    mode = "Active"
  }

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      AWS_REGION_NAME           = data.aws_region.current.name
      DELIVERY_STATUS_QUEUE_URL = aws_sqs_queue.delivery_status.url
      SES_CONFIGURATION_SET     = aws_sesv2_configuration_set.nexus_transactional.configuration_set_name
      FROM_EMAIL_ADDRESS        = "notificaciones@nexusbank.com"
      FROM_EMAIL_NAME           = "Nexus Bank"
      SMS_SENDER_ID             = "NEXUSBNK"
      MAX_SMS_LENGTH            = "160"
      UNSUBSCRIBE_BASE_URL      = "https://app.nexusbank.com"
      LOG_LEVEL                 = "INFO"
      ANDROID_PLATFORM_APP_ARN  = var.android_platform_app_arn
      IOS_PLATFORM_APP_ARN      = var.ios_platform_app_arn
    }
  }

  depends_on = [
    aws_iam_role_policy.notification_dispatcher_lambda,
    aws_iam_role_policy_attachment.notification_dispatcher_lambda_basic
  ]
}

resource "aws_lambda_alias" "notification_dispatcher_live" {
  name             = "live"
  function_name    = aws_lambda_function.notification_dispatcher.function_name
  function_version = aws_lambda_function.notification_dispatcher.version
}

resource "aws_lambda_permission" "notification_dispatch_sns" {
  statement_id  = "AllowSNSInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.notification_dispatcher.function_name
  qualifier     = "live"
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.notification_dispatch.arn
}

# ── CloudWatch Alarm ──────────────────────────────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "notification_high_failure_rate" {
  alarm_name          = "nexus-josue-notification-high-failure-rate"
  namespace           = "Nexus/NotificationDispatcher"
  metric_name         = "DeliveryFailures"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 50
  comparison_operator = "GreaterThanOrEqualToThreshold"
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "notification_dispatch_topic_arn" {
  value = aws_sns_topic.notification_dispatch.arn
}

output "notification_dispatcher_lambda_arn" {
  value = aws_lambda_function.notification_dispatcher.arn
}

output "delivery_status_queue_url" {
  value = aws_sqs_queue.delivery_status.url
}
