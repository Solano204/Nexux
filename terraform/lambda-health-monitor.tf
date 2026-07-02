# ── Nexus Health Monitor Lambda ──────────────────────────────────────────────
# Port of LAMBDA/nexus-health-monitor-lambda/nexus-health-monitor-lambda/template.yaml
# Runs every 5 minutes, checks all 16 local-plane services via /actuator/health
# (or /q/health for the Quarkus service).
# ─────────────────────────────────────────────────────────────────────────────

variable "local_plane_base_url" {
  description = <<-EOT
    Base URL (no port) for local plane health checks, e.g. http://host.
    host.docker.internal only resolves when running locally (sam local /
    same-host networking). For a real AWS Lambda hitting your docker-compose
    stack you must override with a publicly reachable URL (ngrok / Cloudflare
    Tunnel / Tailscale Funnel / public DNS) pointing at the host(s) running
    docker-compose-prod.yml — each of the 16 services is checked at
    "$${LOCAL_PLANE_BASE_URL}:<port>/actuator/health" (or /q/health).
  EOT
  type        = string
  default     = "http://host.docker.internal"
}

variable "consecutive_failures_threshold" {
  description = "Failures before alerting STANDARD tier services"
  type        = number
  default     = 2
}

variable "health_check_timeout_seconds" {
  type    = number
  default = 5
}

# ── SNS Topics ────────────────────────────────────────────────────────────────

resource "aws_sns_topic" "health_alerts_critical" {
  name = "nexus-josue-health-alerts-critical"
}

resource "aws_sns_topic" "health_alerts_standard" {
  name = "nexus-josue-health-alerts-standard"
}

resource "aws_sns_topic" "health_monitor_selfwatch" {
  name = "nexus-josue-health-monitor-selfwatch"
}

# ── IAM Role ──────────────────────────────────────────────────────────────────

resource "aws_iam_role" "health_monitor_lambda" {
  name = "nexus-josue-health-monitor-lambda-role-${var.environment}"

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

resource "aws_iam_role_policy_attachment" "health_monitor_lambda_basic" {
  role       = aws_iam_role.health_monitor_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "health_monitor_lambda_xray" {
  role       = aws_iam_role.health_monitor_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_iam_role_policy" "health_monitor_lambda" {
  name = "nexus-josue-health-monitor-lambda-policy-${var.environment}"
  role = aws_iam_role.health_monitor_lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "HealthMonitorCloudwatch"
        Effect = "Allow"
        Action = [
          "cloudwatch:PutMetricData",
          "cloudwatch:PutMetricAlarm",
          "cloudwatch:DescribeAlarms",
          "cloudwatch:SetAlarmState",
          "cloudwatch:GetMetricStatistics",
          "cloudwatch:PutDashboard"
        ]
        Resource = "*"
      },
      {
        Sid      = "HealthMonitorSnsPublish"
        Effect   = "Allow"
        Action   = "sns:Publish"
        Resource = [aws_sns_topic.health_alerts_critical.arn, aws_sns_topic.health_alerts_standard.arn]
      },
      {
        Sid      = "HealthMonitorPlaneBridgeSecret"
        Effect   = "Allow"
        Action   = "secretsmanager:GetSecretValue"
        Resource = "arn:aws:secretsmanager:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:secret:nexus/*"
      }
    ]
  })
}

# ── Python build (pip deps: boto3, botocore, urllib3, python-json-logger) ─────

resource "null_resource" "health_monitor_build" {
  triggers = {
    requirements_hash = filesha256("${path.module}/../LAMBDA/nexus-health-monitor-lambda/nexus-health-monitor-lambda/requirements.txt")
    source_hash = sha1(join("", [
      for f in sort(fileset("${path.module}/../LAMBDA/nexus-health-monitor-lambda/nexus-health-monitor-lambda/src", "**")) :
      filesha1("${path.module}/../LAMBDA/nexus-health-monitor-lambda/nexus-health-monitor-lambda/src/${f}")
    ]))
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = <<-EOT
      set -e
      rm -rf "${path.module}/build/health-monitor"
      mkdir -p "${path.module}/build/health-monitor"
      pip install -r "${path.module}/../LAMBDA/nexus-health-monitor-lambda/nexus-health-monitor-lambda/requirements.txt" -t "${path.module}/build/health-monitor" --quiet --no-warn-script-location
      cp -r "${path.module}/../LAMBDA/nexus-health-monitor-lambda/nexus-health-monitor-lambda/src" "${path.module}/build/health-monitor/src"
    EOT
  }
}

data "archive_file" "health_monitor_zip" {
  type        = "zip"
  source_dir  = "${path.module}/build/health-monitor"
  output_path = "${path.module}/build/health-monitor.zip"
  depends_on  = [null_resource.health_monitor_build]
}

# ── Lambda Function ───────────────────────────────────────────────────────────

resource "aws_lambda_function" "health_monitor" {
  function_name = "nexus-josue-health-monitor-lambda"
  role          = aws_iam_role.health_monitor_lambda.arn
  handler       = "src.handler.check_platform_health"
  runtime       = "python3.12"
  memory_size   = 256
  timeout       = 120

  filename         = data.archive_file.health_monitor_zip.output_path
  source_code_hash = data.archive_file.health_monitor_zip.output_base64sha256

  tracing_config {
    mode = "Active"
  }

  environment {
    variables = {
      LOCAL_PLANE_BASE_URL           = var.local_plane_base_url
      PLANE_BRIDGE_SECRET            = local.plane_bridge_secret_value
      CRITICAL_ALERT_TOPIC_ARN       = aws_sns_topic.health_alerts_critical.arn
      STANDARD_ALERT_TOPIC_ARN       = aws_sns_topic.health_alerts_standard.arn
      CLOUDWATCH_NAMESPACE           = "Nexus/HealthMonitor"
      CONSECUTIVE_FAILURES_THRESHOLD = tostring(var.consecutive_failures_threshold)
      HEALTH_CHECK_TIMEOUT_SECONDS   = tostring(var.health_check_timeout_seconds)
      PARALLEL_CHECKS                = "true"
      MAX_WORKERS                    = "8"
      ENVIRONMENT                    = var.environment
      AWS_REGION_NAME                = data.aws_region.current.name
    }
  }
}

# ── EventBridge Schedule (every 5 minutes) ────────────────────────────────────

resource "aws_cloudwatch_event_rule" "health_check_schedule" {
  name                = "nexus-josue-health-check-schedule"
  description         = "Check platform health every 5 minutes"
  schedule_expression = "rate(5 minutes)"
  state               = "ENABLED"
}

resource "aws_cloudwatch_event_target" "health_check_schedule" {
  rule = aws_cloudwatch_event_rule.health_check_schedule.name
  arn  = aws_lambda_function.health_monitor.arn
}

resource "aws_lambda_permission" "health_monitor_events" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.health_monitor.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.health_check_schedule.arn
}

# ── CloudWatch Alarms (monitor the monitor) ───────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "health_monitor_lambda_errors" {
  alarm_name          = "nexus-josue-health-monitor-lambda-errors"
  alarm_description   = "The health monitor Lambda itself is failing. Platform health may not be monitored. Investigate immediately."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.health_monitor.function_name }
  statistic           = "Sum"
  period              = 900
  evaluation_periods  = 1
  threshold           = 2
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.health_monitor_selfwatch.arn]
}

resource "aws_cloudwatch_metric_alarm" "health_monitor_lambda_throttles" {
  alarm_name          = "nexus-josue-health-monitor-lambda-throttled"
  namespace           = "AWS/Lambda"
  metric_name         = "Throttles"
  dimensions          = { FunctionName = aws_lambda_function.health_monitor.function_name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  alarm_actions       = [aws_sns_topic.health_monitor_selfwatch.arn]
}

# ── Outputs ────────────────────────────────────────────────────────────────────

output "health_monitor_lambda_arn" {
  value = aws_lambda_function.health_monitor.arn
}

output "health_alerts_critical_topic_arn" {
  value = aws_sns_topic.health_alerts_critical.arn
}

output "health_alerts_standard_topic_arn" {
  value = aws_sns_topic.health_alerts_standard.arn
}
