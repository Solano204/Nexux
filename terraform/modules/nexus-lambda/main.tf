# nexus-lambda module — main
# Extracted from the pattern already repeated 8 times by hand across
# lambda-*.tf (fraud-alert, health-monitor, kyc-rekognition,
# notification-dispatcher, analytics-aggregator, auth, payment-processor,
# reporting) - see CHANGES-BESTPRACTICES/05_TERRAFORM_AWS_CHANGES.md
# Section 3 for the audit. Covers the 4 real trigger types found in that
# audit: SQS, EventBridge (schedule), S3, SNS.

locals {
  trigger_count = length([
    for t in [var.sqs_trigger, var.eventbridge_trigger, var.s3_trigger, var.sns_trigger] :
    t if t != null
  ])
}

# Terraform 1.5+ check block - fails plan with a clear message instead of
# silently creating a Lambda with zero triggers (or ambiguously with two).
check "exactly_one_trigger" {
  assert {
    condition     = local.trigger_count == 1
    error_message = "nexus-lambda module (${var.function_name}) requires exactly one trigger type (sqs_trigger/eventbridge_trigger/s3_trigger/sns_trigger), got ${local.trigger_count}."
  }
}

resource "aws_iam_role" "this" {
  name = "${var.function_name}-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "basic_execution" {
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "xray" {
  count      = var.enable_xray ? 1 : 0
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_iam_role_policy" "custom" {
  count = length(var.iam_policy_statements) > 0 ? 1 : 0
  name  = "${var.function_name}-policy"
  role  = aws_iam_role.this.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      for s in var.iam_policy_statements : {
        Sid      = s.sid
        Effect   = "Allow"
        Action   = s.actions
        Resource = s.resources
      }
    ]
  })
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/aws/lambda/${var.function_name}"
  retention_in_days = 30
  tags              = var.tags
}

resource "aws_lambda_function" "this" {
  function_name = var.function_name
  role          = aws_iam_role.this.arn
  handler       = var.handler
  runtime       = var.runtime

  filename         = var.jar_path
  source_code_hash = var.source_hash

  memory_size                    = var.memory_size
  timeout                        = var.timeout
  reserved_concurrent_executions = var.reserved_concurrent_executions
  publish                        = true

  dynamic "tracing_config" {
    for_each = var.enable_xray ? [1] : []
    content {
      mode = "Active"
    }
  }

  dynamic "snap_start" {
    for_each = var.enable_snap_start ? [1] : []
    content {
      apply_on = "PublishedVersions"
    }
  }

  environment {
    variables = var.environment_variables
  }

  tags = var.tags

  depends_on = [
    aws_cloudwatch_log_group.this,
    aws_iam_role_policy_attachment.basic_execution
  ]
}

resource "aws_lambda_alias" "live" {
  name             = "live"
  function_name    = aws_lambda_function.this.function_name
  function_version = aws_lambda_function.this.version
}

# ── Triggers ─────────────────────────────────────────────────────────

resource "aws_lambda_event_source_mapping" "sqs" {
  count                               = var.sqs_trigger != null ? 1 : 0
  event_source_arn                    = var.sqs_trigger.queue_arn
  function_name                       = aws_lambda_alias.live.arn
  batch_size                          = var.sqs_trigger.batch_size
  maximum_batching_window_in_seconds  = var.sqs_trigger.maximum_batching_window_in_seconds
  function_response_types             = ["ReportBatchItemFailures"]
}

resource "aws_cloudwatch_event_rule" "eventbridge" {
  count               = var.eventbridge_trigger != null ? 1 : 0
  name                = "${var.function_name}-schedule"
  schedule_expression = var.eventbridge_trigger.schedule_expression
}

resource "aws_cloudwatch_event_target" "eventbridge" {
  count = var.eventbridge_trigger != null ? 1 : 0
  rule  = aws_cloudwatch_event_rule.eventbridge[0].name
  arn   = aws_lambda_alias.live.arn
}

resource "aws_lambda_permission" "eventbridge" {
  count         = var.eventbridge_trigger != null ? 1 : 0
  statement_id  = "AllowEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_alias.live.function_name
  qualifier     = aws_lambda_alias.live.name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.eventbridge[0].arn
}

resource "aws_lambda_permission" "s3" {
  count         = var.s3_trigger != null ? 1 : 0
  statement_id  = "AllowS3"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_alias.live.function_name
  qualifier     = aws_lambda_alias.live.name
  principal     = "s3.amazonaws.com"
  source_arn    = var.s3_trigger.bucket_arn
}

resource "aws_s3_bucket_notification" "this" {
  count  = var.s3_trigger != null ? 1 : 0
  bucket = var.s3_trigger.bucket_id

  lambda_function {
    lambda_function_arn = aws_lambda_alias.live.arn
    events               = var.s3_trigger.events
    filter_prefix        = var.s3_trigger.filter_prefix
  }

  depends_on = [aws_lambda_permission.s3]
}

resource "aws_lambda_permission" "sns" {
  count         = var.sns_trigger != null ? 1 : 0
  statement_id  = "AllowSNS"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_alias.live.function_name
  qualifier     = aws_lambda_alias.live.name
  principal     = "sns.amazonaws.com"
  source_arn    = var.sns_trigger.topic_arn
}

resource "aws_sns_topic_subscription" "this" {
  count     = var.sns_trigger != null ? 1 : 0
  topic_arn = var.sns_trigger.topic_arn
  protocol  = "lambda"
  endpoint  = aws_lambda_alias.live.arn
}
