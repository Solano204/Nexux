# ── Nexus Reporting Lambda ────────────────────────────────────────────────────
# Port of LAMBDA/nexus-reporting-lambda/nexus-reporting-lambda/template.yaml
# Scheduled daily (EventBridge). Reads tables it doesn't own by static ARN
# string (not Terraform resource reference) to avoid coupling this file to
# lambda-analytics-aggregator.tf / lambda-fraud-alert.tf — table names below
# must stay in sync with those files' `name` values.
# ─────────────────────────────────────────────────────────────────────────────

variable "reporting_lambda_jar_path" {
  description = "Path to the built shaded jar. Built automatically by null_resource.reporting_lambda_build on apply."
  type        = string
  default     = "../LAMBDA/nexus-reporting-lambda/nexus-reporting-lambda/target/nexus-reporting-lambda-1.0.0.jar"
}

resource "null_resource" "reporting_lambda_build" {
  triggers = {
    pom_hash = filesha256("../LAMBDA/nexus-reporting-lambda/nexus-reporting-lambda/pom.xml")
    source_hash = sha1(join("", [
      for f in sort(fileset("../LAMBDA/nexus-reporting-lambda/nexus-reporting-lambda/src/main", "**")) :
      filesha1("../LAMBDA/nexus-reporting-lambda/nexus-reporting-lambda/src/main/${f}")
    ]))
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = "JAVA_HOME='/c/Program Files/Java/jdk-21' mvn -f ../LAMBDA/nexus-reporting-lambda/nexus-reporting-lambda/pom.xml clean package -DskipTests -q"
  }
}

data "local_file" "reporting_lambda_jar" {
  filename   = var.reporting_lambda_jar_path
  depends_on = [null_resource.reporting_lambda_build]
}

variable "ops_notification_email" {
  description = "Email address notified when a daily report finishes generating"
  type        = string
}

# ── KMS Key ───────────────────────────────────────────────────────────────────

resource "aws_kms_key" "reports" {
  description         = "KMS key for nexus-reports S3 bucket encryption"
  enable_key_rotation = true

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowRootAccount"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      }
    ]
  })
}

# ── S3 Bucket ─────────────────────────────────────────────────────────────────
# Bucket name embeds account_id — "nexus-reports-prod" alone collided with
# someone else's bucket (S3 bucket names are globally unique across all AWS).

resource "aws_s3_bucket" "reports" {
  bucket = "nexus-josue-reports-${var.environment}-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "reports" {
  bucket = aws_s3_bucket.reports.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "reports" {
  bucket = aws_s3_bucket.reports.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.reports.arn
    }
  }
}

resource "aws_s3_bucket_versioning" "reports" {
  bucket = aws_s3_bucket.reports.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "reports" {
  bucket     = aws_s3_bucket.reports.id
  depends_on = [aws_s3_bucket_versioning.reports]

  rule {
    id     = "compliance-retention-7-years"
    status = "Enabled"

    filter {
      prefix = "daily/compliance/"
    }

    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }
    transition {
      days          = 365
      storage_class = "GLACIER"
    }
  }

  rule {
    id     = "operations-retention-90-days"
    status = "Enabled"

    filter {
      prefix = "daily/operations/"
    }

    expiration {
      days = 90
    }
  }

  rule {
    id     = "bi-retention-1-year"
    status = "Enabled"

    filter {
      prefix = "daily/business-intelligence/"
    }

    transition {
      days          = 90
      storage_class = "STANDARD_IA"
    }

    expiration {
      days = 365
    }
  }

  rule {
    id     = "user-statements-retention-1-year"
    status = "Enabled"

    filter {
      prefix = "daily/user-statements/"
    }

    expiration {
      days = 365
    }
  }
}

# ── SNS Topic ─────────────────────────────────────────────────────────────────

resource "aws_sns_topic" "report_completed" {
  name = "nexus-josue-report-completed"
}

resource "aws_sns_topic_subscription" "report_completed_email" {
  topic_arn = aws_sns_topic.report_completed.arn
  protocol  = "email"
  endpoint  = var.ops_notification_email
}

# ── IAM Role ──────────────────────────────────────────────────────────────────
# Reads tables owned by lambda-analytics-aggregator.tf / lambda-fraud-alert.tf
# by hardcoded ARN (see file header) — keep these names in sync with those
# files' `nexus-josue-*` table names.

resource "aws_iam_role" "reporting_lambda" {
  name = "nexus-josue-reporting-lambda-role-${var.environment}"

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

resource "aws_iam_role_policy_attachment" "reporting_lambda_basic" {
  role       = aws_iam_role.reporting_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "reporting_lambda_xray" {
  role       = aws_iam_role.reporting_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_iam_role_policy" "reporting_lambda" {
  name = "nexus-josue-reporting-lambda-policy-${var.environment}"
  role = aws_iam_role.reporting_lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReportingTablesRead"
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:Query",
          "dynamodb:Scan",
          "dynamodb:BatchGetItem",
          "dynamodb:DescribeTable"
        ]
        Resource = [
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-transactions",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-fraud-alerts",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-analytics-daily",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-analytics-category",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-analytics-hourly-volume",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-analytics-platform-metrics",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-transactions/index/*",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-fraud-alerts/index/*",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-analytics-daily/index/*",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-analytics-category/index/*",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-analytics-hourly-volume/index/*",
          "arn:aws:dynamodb:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:table/nexus-josue-analytics-platform-metrics/index/*"
        ]
      },
      {
        Sid    = "ReportsBucketCrud"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:PutObjectTagging",
          "s3:GetObjectTagging",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.reports.arn,
          "${aws_s3_bucket.reports.arn}/*"
        ]
      },
      {
        Sid      = "ReportCompletedPublish"
        Effect   = "Allow"
        Action   = "sns:Publish"
        Resource = aws_sns_topic.report_completed.arn
      },
      {
        Sid      = "ReportingMetrics"
        Effect   = "Allow"
        Action   = "cloudwatch:PutMetricData"
        Resource = "*"
      },
      {
        Sid    = "ReportsKmsUsage"
        Effect = "Allow"
        Action = [
          "kms:Encrypt",
          "kms:Decrypt",
          "kms:GenerateDataKey"
        ]
        Resource = aws_kms_key.reports.arn
      },
      {
        # fraud_alerts DynamoDB table (owned by lambda-fraud-alert.tf) is
        # encrypted with its own KMS key — reading it via ReportingTablesRead
        # needs Decrypt on that key too, not just aws_kms_key.reports.
        Sid      = "FraudAlertsKmsDecrypt"
        Effect   = "Allow"
        Action   = "kms:Decrypt"
        Resource = aws_kms_key.fraud_alerts.arn
      }
    ]
  })
}

# ── Lambda Function ───────────────────────────────────────────────────────────

resource "aws_lambda_function" "reporting" {
  function_name = "nexus-josue-reporting-lambda"
  role          = aws_iam_role.reporting_lambda.arn
  handler       = "com.nexus.reporting.ReportingHandler::handleRequest"
  runtime       = "java21"

  filename         = data.local_file.reporting_lambda_jar.filename
  source_code_hash = data.local_file.reporting_lambda_jar.content_base64sha256

  memory_size = 1024
  timeout     = 900
  publish     = true

  ephemeral_storage {
    size = 2048
  }

  tracing_config {
    mode = "Active"
  }

  environment {
    variables = {
      TRANSACTIONS_TABLE                = "nexus-josue-transactions"
      FRAUD_ALERTS_TABLE                 = "nexus-josue-fraud-alerts"
      ANALYTICS_DAILY_TABLE              = "nexus-josue-analytics-daily"
      ANALYTICS_CATEGORY_TABLE           = "nexus-josue-analytics-category"
      ANALYTICS_HOURLY_VOLUME_TABLE      = "nexus-josue-analytics-hourly-volume"
      PLATFORM_METRICS_TABLE             = "nexus-josue-analytics-platform-metrics"
      REPORTS_BUCKET                     = aws_s3_bucket.reports.bucket
      REPORTS_KMS_KEY_ARN                = aws_kms_key.reports.arn
      NOTIFICATION_SNS_TOPIC_ARN         = aws_sns_topic.report_completed.arn
      CLOUDWATCH_NAMESPACE               = "Nexus/Reporting"
      PLATFORM_NAME                      = "Nexus Bank"
      ENVIRONMENT                        = var.environment
      TIMEZONE                           = "America/Mexico_City"
      REPORT_LANGUAGE                    = "es"
      REPORT_CURRENCY                    = "MXN"
      COMPLIANCE_REPORT_RETENTION_YEARS  = "7"
    }
  }

  depends_on = [
    aws_iam_role_policy.reporting_lambda,
    aws_iam_role_policy_attachment.reporting_lambda_basic
  ]
}

resource "aws_lambda_alias" "reporting_live" {
  name             = "live"
  function_name    = aws_lambda_function.reporting.function_name
  function_version = aws_lambda_function.reporting.version
}

# ── EventBridge Schedule ──────────────────────────────────────────────────────

resource "aws_cloudwatch_event_rule" "daily_report_schedule" {
  name                = "nexus-josue-daily-report-schedule"
  description         = "Daily report generation at 6am Mexico City (UTC-6)"
  schedule_expression = "cron(0 12 * * ? *)"
}

resource "aws_cloudwatch_event_target" "daily_report_schedule" {
  rule = aws_cloudwatch_event_rule.daily_report_schedule.name
  arn  = aws_lambda_function.reporting.arn

  input = jsonencode({
    reportDate  = "YESTERDAY"
    reportTypes = ["OPERATIONS", "COMPLIANCE", "BUSINESS_INTELLIGENCE", "USER_STATEMENTS"]
  })
}

resource "aws_lambda_permission" "reporting_events" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.reporting.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.daily_report_schedule.arn
}

# ── CloudWatch Alarms ─────────────────────────────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "report_generation_failure" {
  alarm_name          = "nexus-josue-reporting-failure"
  alarm_description   = "Report generation Lambda error. Daily reports may be missing. Investigate and re-run manually if needed."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.reporting.function_name }
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "report_not_generated" {
  alarm_name          = "nexus-josue-daily-report-not-generated"
  alarm_description   = "No ReportGenerationComplete metric today. Lambda may not have run. TreatMissingData=breaching catches complete Lambda failures."
  namespace           = "Nexus/Reporting"
  metric_name         = "ReportGenerationComplete"
  statistic           = "Sum"
  period              = 86400
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
}

resource "aws_cloudwatch_metric_alarm" "report_duration_high" {
  alarm_name          = "nexus-josue-reporting-duration-high"
  alarm_description   = "Report generation took over 10 minutes. Approaching 15min timeout. May need Step Functions split or memory increase."
  namespace           = "Nexus/Reporting"
  metric_name         = "ReportGenerationDuration"
  statistic           = "Maximum"
  period              = 86400
  evaluation_periods  = 1
  threshold           = 600000
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "reporting_lambda_arn" {
  value = aws_lambda_function.reporting.arn
}

output "reports_bucket_name" {
  value = aws_s3_bucket.reports.bucket
}

output "reports_bucket_arn" {
  value = aws_s3_bucket.reports.arn
}

output "report_completed_topic_arn" {
  value = aws_sns_topic.report_completed.arn
}
