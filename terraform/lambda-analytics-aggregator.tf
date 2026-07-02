# ── Nexus Analytics Aggregator Lambda ────────────────────────────────────────
# Port of LAMBDA/nexus-analytics-aggregator-lambda/nexus-analytics-aggregator-lambda/template.yaml
# DynamoDB Streams trigger: reacts to transaction state changes, maintains
# 6 analytics tables with incremental updates.
#
# This file OWNS nexus-transactions + the 6 analytics tables. lambda-reporting.tf
# reads them by static ARN string (not Terraform resource reference) — table
# names below must not change without updating that file too.
# ─────────────────────────────────────────────────────────────────────────────

# ── DynamoDB Tables ───────────────────────────────────────────────────────────

resource "aws_dynamodb_table" "transactions" {
  name         = "nexus-josue-transactions"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

  stream_enabled   = true
  stream_view_type = "NEW_AND_OLD_IMAGES"

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

resource "aws_dynamodb_table" "daily_stats" {
  name         = "nexus-josue-analytics-daily"
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

resource "aws_dynamodb_table" "category_stats" {
  name         = "nexus-josue-analytics-category"
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

resource "aws_dynamodb_table" "hourly_volume" {
  name         = "nexus-josue-analytics-hourly-volume"
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

resource "aws_dynamodb_table" "merchant_frequency" {
  name         = "nexus-josue-analytics-merchant-frequency"
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

resource "aws_dynamodb_table" "user_summary" {
  name         = "nexus-josue-analytics-user-summary"
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
  # No TTL — one permanent row per user, continuously updated
}

resource "aws_dynamodb_table" "platform_metrics" {
  name         = "nexus-josue-analytics-platform-metrics"
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
  # No TTL — REALTIME row continuously overwritten
}

# ── DLQ ───────────────────────────────────────────────────────────────────────

resource "aws_sqs_queue" "analytics_aggregator_dlq" {
  name                      = "nexus-josue-analytics-aggregator-dlq"
  message_retention_seconds = 86400
}

# ── IAM Role ──────────────────────────────────────────────────────────────────

resource "aws_iam_role" "analytics_aggregator_lambda" {
  name = "nexus-josue-analytics-aggregator-lambda-role-${var.environment}"

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

resource "aws_iam_role_policy_attachment" "analytics_aggregator_lambda_basic" {
  role       = aws_iam_role.analytics_aggregator_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "analytics_aggregator_lambda" {
  name = "nexus-josue-analytics-aggregator-lambda-policy-${var.environment}"
  role = aws_iam_role.analytics_aggregator_lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "TransactionsStreamRead"
        Effect = "Allow"
        Action = [
          "dynamodb:DescribeStream",
          "dynamodb:GetRecords",
          "dynamodb:GetShardIterator",
          "dynamodb:ListStreams"
        ]
        Resource = aws_dynamodb_table.transactions.stream_arn
      },
      {
        Sid    = "AnalyticsTablesCrud"
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query",
          "dynamodb:BatchWriteItem"
        ]
        Resource = [
          aws_dynamodb_table.daily_stats.arn,
          aws_dynamodb_table.category_stats.arn,
          aws_dynamodb_table.hourly_volume.arn,
          aws_dynamodb_table.merchant_frequency.arn,
          aws_dynamodb_table.user_summary.arn,
          aws_dynamodb_table.platform_metrics.arn
        ]
      },
      {
        Sid      = "AnalyticsAggregatorMetrics"
        Effect   = "Allow"
        Action   = "cloudwatch:PutMetricData"
        Resource = "*"
      },
      {
        Sid      = "AnalyticsAggregatorDlq"
        Effect   = "Allow"
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.analytics_aggregator_dlq.arn
      }
    ]
  })
}

# ── Python build (pip deps: boto3, botocore, python-json-logger) ──────────────

resource "null_resource" "analytics_aggregator_build" {
  triggers = {
    requirements_hash = filesha256("${path.module}/../LAMBDA/nexus-analytics-aggregator-lambda/nexus-analytics-aggregator-lambda/requirements.txt")
    source_hash = sha1(join("", [
      for f in sort(fileset("${path.module}/../LAMBDA/nexus-analytics-aggregator-lambda/nexus-analytics-aggregator-lambda/src", "**")) :
      filesha1("${path.module}/../LAMBDA/nexus-analytics-aggregator-lambda/nexus-analytics-aggregator-lambda/src/${f}")
    ]))
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = <<-EOT
      set -e
      rm -rf "${path.module}/build/analytics-aggregator"
      mkdir -p "${path.module}/build/analytics-aggregator"
      pip install -r "${path.module}/../LAMBDA/nexus-analytics-aggregator-lambda/nexus-analytics-aggregator-lambda/requirements.txt" -t "${path.module}/build/analytics-aggregator" --quiet --no-warn-script-location
      cp -r "${path.module}/../LAMBDA/nexus-analytics-aggregator-lambda/nexus-analytics-aggregator-lambda/src" "${path.module}/build/analytics-aggregator/src"
    EOT
  }
}

data "archive_file" "analytics_aggregator_zip" {
  type        = "zip"
  source_dir  = "${path.module}/build/analytics-aggregator"
  output_path = "${path.module}/build/analytics-aggregator.zip"
  depends_on  = [null_resource.analytics_aggregator_build]
}

# ── Lambda Function ───────────────────────────────────────────────────────────

resource "aws_lambda_function" "analytics_aggregator" {
  function_name = "nexus-josue-analytics-aggregator-lambda"
  role          = aws_iam_role.analytics_aggregator_lambda.arn
  handler       = "src.handler.aggregate_transactions"
  runtime       = "python3.12"
  memory_size   = 256
  timeout       = 60

  filename         = data.archive_file.analytics_aggregator_zip.output_path
  source_code_hash = data.archive_file.analytics_aggregator_zip.output_base64sha256

  tracing_config {
    mode = "Active"
  }

  environment {
    variables = {
      TRANSACTIONS_TABLE       = "nexus-josue-transactions"
      DAILY_STATS_TABLE        = "nexus-josue-analytics-daily"
      CATEGORY_STATS_TABLE     = "nexus-josue-analytics-category"
      HOURLY_VOLUME_TABLE      = "nexus-josue-analytics-hourly-volume"
      MERCHANT_FREQUENCY_TABLE = "nexus-josue-analytics-merchant-frequency"
      USER_SUMMARY_TABLE       = "nexus-josue-analytics-user-summary"
      PLATFORM_METRICS_TABLE   = "nexus-josue-analytics-platform-metrics"
      CLOUDWATCH_NAMESPACE     = "Nexus/Analytics"
      LOG_LEVEL                = "INFO"
      AWS_REGION_NAME          = data.aws_region.current.name
    }
  }
}

# ── DynamoDB Streams Trigger ──────────────────────────────────────────────────

resource "aws_lambda_event_source_mapping" "transactions_stream" {
  event_source_arn = aws_dynamodb_table.transactions.stream_arn
  function_name    = aws_lambda_function.analytics_aggregator.arn

  starting_position                  = "LATEST"
  batch_size                         = 100
  maximum_batching_window_in_seconds = 5

  # On failure, split batch in half and retry each half — isolates a single
  # bad record to a batch of 1 instead of blocking the whole shard.
  bisect_batch_on_function_error = true

  destination_config {
    on_failure {
      destination_arn = aws_sqs_queue.analytics_aggregator_dlq.arn
    }
  }

  filter_criteria {
    filter {
      pattern = jsonencode({ eventName = ["INSERT", "MODIFY"] })
    }
  }
}

# ── CloudWatch Alarm ──────────────────────────────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "analytics_aggregation_errors" {
  alarm_name          = "nexus-josue-analytics-aggregation-errors"
  alarm_description   = "Analytics aggregation errors cause data inconsistency. Investigate immediately."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.analytics_aggregator.function_name }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
}

# ── Outputs ────────────────────────────────────────────────────────────────────

output "analytics_aggregator_lambda_arn" {
  value = aws_lambda_function.analytics_aggregator.arn
}

output "transactions_table_name" {
  value = aws_dynamodb_table.transactions.name
}

output "transactions_table_stream_arn" {
  value = aws_dynamodb_table.transactions.stream_arn
}

output "daily_stats_table_name" {
  value = aws_dynamodb_table.daily_stats.name
}
