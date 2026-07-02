# ── Nexus Payment Processor Lambda ───────────────────────────────────────────
# Port of LAMBDA/nexus-payment-processor-lambda/nexus-payment-processor-lambda/template.yaml
# SQS-triggered. Idempotent processing backed by DynamoDB; publishes result to
# an SNS topic that Plane A can subscribe to (or the Lambda calls back over
# HTTP in "bridge mode" — see kafka_bridge_http_url below).
# ─────────────────────────────────────────────────────────────────────────────

variable "payment_processor_lambda_jar_path" {
  description = "Path to the built shaded jar. Built automatically by null_resource.payment_processor_lambda_build on apply."
  type        = string
  default     = "../LAMBDA/nexus-payment-processor-lambda/nexus-payment-processor-lambda/target/nexus-payment-processor-lambda-1.0.0.jar"
}

resource "null_resource" "payment_processor_lambda_build" {
  triggers = {
    pom_hash = filesha256("../LAMBDA/nexus-payment-processor-lambda/nexus-payment-processor-lambda/pom.xml")
    source_hash = sha1(join("", [
      for f in sort(fileset("../LAMBDA/nexus-payment-processor-lambda/nexus-payment-processor-lambda/src/main", "**")) :
      filesha1("../LAMBDA/nexus-payment-processor-lambda/nexus-payment-processor-lambda/src/main/${f}")
    ]))
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = "JAVA_HOME='/c/Program Files/Java/jdk-21' mvn -f ../LAMBDA/nexus-payment-processor-lambda/nexus-payment-processor-lambda/pom.xml clean package -DskipTests -q"
  }
}

data "local_file" "payment_processor_lambda_jar" {
  filename   = var.payment_processor_lambda_jar_path
  depends_on = [null_resource.payment_processor_lambda_build]
}

variable "msk_bootstrap_servers" {
  description = "MSK bootstrap servers, used when KAFKA_BRIDGE_MODE=KAFKA instead of HTTP. Null while using the HTTP bridge."
  type        = string
  default     = null
}

variable "kafka_bridge_http_url" {
  description = <<-EOT
    Plane A API Gateway URL used in HTTP bridge mode (KAFKA_BRIDGE_MODE=HTTP).
    host.docker.internal only resolves for `sam local` on the same machine —
    a real AWS Lambda cannot reach a machine behind NAT. Override with a
    publicly reachable tunnel (ngrok / Cloudflare Tunnel / Tailscale Funnel)
    or your real API Gateway domain once you deploy for real.
  EOT
  type        = string
  default     = "http://host.docker.internal:8080"
}

# ── SQS Queues ────────────────────────────────────────────────────────────────

resource "aws_sqs_queue" "payment_incoming" {
  name                       = "nexus-josue-payment-incoming"
  visibility_timeout_seconds = 70
  message_retention_seconds  = 86400
  receive_wait_time_seconds  = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.payment_incoming_dlq.arn
    maxReceiveCount     = 3
  })
}

resource "aws_sqs_queue" "payment_incoming_dlq" {
  name                      = "nexus-josue-payment-incoming-dlq"
  message_retention_seconds = 1209600
}

# ── DynamoDB Table ────────────────────────────────────────────────────────────

resource "aws_dynamodb_table" "payment_idempotency" {
  name         = "nexus-josue-payment-idempotency"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"

  attribute {
    name = "PK"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }
}

# ── SNS Topic ─────────────────────────────────────────────────────────────────

resource "aws_sns_topic" "payment_processed" {
  name = "nexus-josue-payment-processed"
}

# ── IAM Role ──────────────────────────────────────────────────────────────────

resource "aws_iam_role" "payment_processor_lambda" {
  name = "nexus-josue-payment-processor-lambda-role-${var.environment}"

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

resource "aws_iam_role_policy_attachment" "payment_processor_lambda_basic" {
  role       = aws_iam_role.payment_processor_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "payment_processor_lambda_xray" {
  role       = aws_iam_role.payment_processor_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_iam_role_policy" "payment_processor_lambda" {
  name = "nexus-josue-payment-processor-lambda-policy-${var.environment}"
  role = aws_iam_role.payment_processor_lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "PaymentIncomingConsume"
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = aws_sqs_queue.payment_incoming.arn
      },
      {
        Sid      = "PaymentProcessedPublish"
        Effect   = "Allow"
        Action   = "sns:Publish"
        Resource = aws_sns_topic.payment_processed.arn
      },
      {
        Sid    = "PaymentIdempotencyCrud"
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query"
        ]
        Resource = aws_dynamodb_table.payment_idempotency.arn
      },
      {
        Sid      = "PaymentPlaneBridgeSecret"
        Effect   = "Allow"
        Action   = "secretsmanager:GetSecretValue"
        Resource = "arn:aws:secretsmanager:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:secret:nexus/*"
      }
    ]
  })
}

# ── Lambda Function ───────────────────────────────────────────────────────────

resource "aws_lambda_function" "payment_processor" {
  function_name = "nexus-josue-payment-processor-lambda"
  role          = aws_iam_role.payment_processor_lambda.arn
  handler       = "com.nexus.payment.lambda.PaymentProcessorHandler::handleRequest"
  runtime       = "java21"

  filename         = data.local_file.payment_processor_lambda_jar.filename
  source_code_hash = data.local_file.payment_processor_lambda_jar.content_base64sha256

  memory_size = 512
  timeout     = 60
  publish     = true

  tracing_config {
    mode = "Active"
  }

  environment {
    variables = {
      AWS_REGION_NAME            = data.aws_region.current.name
      DYNAMODB_IDEMPOTENCY_TABLE = aws_dynamodb_table.payment_idempotency.name
      SNS_PAYMENT_PROCESSED_ARN  = aws_sns_topic.payment_processed.arn
      KAFKA_BRIDGE_MODE          = "HTTP"
      KAFKA_BRIDGE_HTTP_URL      = var.kafka_bridge_http_url
      MSK_BOOTSTRAP_SERVERS      = var.msk_bootstrap_servers
      PLANE_BRIDGE_SECRET        = local.plane_bridge_secret_value
    }
  }

  depends_on = [
    aws_iam_role_policy.payment_processor_lambda,
    aws_iam_role_policy_attachment.payment_processor_lambda_basic
  ]
}

resource "aws_lambda_alias" "payment_processor_live" {
  name             = "live"
  function_name    = aws_lambda_function.payment_processor.function_name
  function_version = aws_lambda_function.payment_processor.version
}

# ── SQS Trigger ───────────────────────────────────────────────────────────────

resource "aws_lambda_event_source_mapping" "payment_incoming_sqs" {
  event_source_arn = aws_sqs_queue.payment_incoming.arn
  function_name    = aws_lambda_alias.payment_processor_live.arn

  batch_size                         = 10
  maximum_batching_window_in_seconds = 5

  function_response_types = ["ReportBatchItemFailures"]
}

# ── CloudWatch Alarm ──────────────────────────────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "payment_dlq_messages" {
  alarm_name          = "nexus-josue-payment-dlq-messages"
  alarm_description   = "Payment messages are landing in DLQ. Requires immediate investigation."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.payment_incoming_dlq.name }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "payment_incoming_queue_url" {
  value = aws_sqs_queue.payment_incoming.url
}

output "payment_processed_topic_arn" {
  value = aws_sns_topic.payment_processed.arn
}

output "payment_processor_lambda_arn" {
  value = aws_lambda_function.payment_processor.arn
}
