# ── Nexus KYC Rekognition Lambda ─────────────────────────────────────────────
# Port of LAMBDA/nexus-kyc-rekognition-lambda/nexus-kyc-rekognition-lambda/template.yaml
# Triggered by S3 ObjectCreated events on the existing KYC documents bucket
# (owned by s3.tf). Runs Rekognition quality/text checks and publishes the
# result onto its own results queue for nexus-ai-kyc-service to consume.
# ─────────────────────────────────────────────────────────────────────────────

# ── SQS Queues ────────────────────────────────────────────────────────────────

resource "aws_sqs_queue" "kyc_rekognition_results" {
  name                       = "nexus-josue-kyc-rekognition-results"
  visibility_timeout_seconds = 300
  message_retention_seconds  = 3600

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.kyc_rekognition_results_dlq.arn
    maxReceiveCount     = 3
  })
}

resource "aws_sqs_queue" "kyc_rekognition_results_dlq" {
  name                      = "nexus-josue-kyc-rekognition-results-dlq"
  message_retention_seconds = 86400
}

# ── IAM Role ──────────────────────────────────────────────────────────────────

resource "aws_iam_role" "kyc_rekognition_lambda" {
  name = "nexus-josue-kyc-rekognition-lambda-role-${var.environment}"

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

resource "aws_iam_role_policy_attachment" "kyc_rekognition_lambda_basic" {
  role       = aws_iam_role.kyc_rekognition_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "kyc_rekognition_lambda_xray" {
  role       = aws_iam_role.kyc_rekognition_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_iam_role_policy" "kyc_rekognition_lambda" {
  name = "nexus-josue-kyc-rekognition-lambda-policy-${var.environment}"
  role = aws_iam_role.kyc_rekognition_lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "KycDocumentRead"
        Effect   = "Allow"
        Action   = "s3:GetObject"
        Resource = "${aws_s3_bucket.kyc_documents.arn}/kyc/*"
      },
      {
        Sid    = "KycDocumentTagging"
        Effect = "Allow"
        Action = [
          "s3:PutObjectTagging",
          "s3:GetObjectTagging"
        ]
        Resource = "${aws_s3_bucket.kyc_documents.arn}/kyc/*"
      },
      {
        Sid    = "RekognitionDetect"
        Effect = "Allow"
        Action = [
          "rekognition:DetectText",
          "rekognition:DetectFaces"
        ]
        Resource = "*"
      },
      {
        Sid      = "KycResultsPublish"
        Effect   = "Allow"
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.kyc_rekognition_results.arn
      },
      {
        Sid      = "KycRekognitionMetrics"
        Effect   = "Allow"
        Action   = "cloudwatch:PutMetricData"
        Resource = "*"
      }
    ]
  })
}

# ── Python build (pip deps: boto3, Pillow) ────────────────────────────────────

resource "null_resource" "kyc_rekognition_build" {
  triggers = {
    requirements_hash = filesha256("${path.module}/../LAMBDA/nexus-kyc-rekognition-lambda/nexus-kyc-rekognition-lambda/requirements.txt")
    source_hash = sha1(join("", [
      for f in sort(fileset("${path.module}/../LAMBDA/nexus-kyc-rekognition-lambda/nexus-kyc-rekognition-lambda/src", "**")) :
      filesha1("${path.module}/../LAMBDA/nexus-kyc-rekognition-lambda/nexus-kyc-rekognition-lambda/src/${f}")
    ]))
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = <<-EOT
      set -e
      rm -rf "${path.module}/build/kyc-rekognition"
      mkdir -p "${path.module}/build/kyc-rekognition"
      pip install -r "${path.module}/../LAMBDA/nexus-kyc-rekognition-lambda/nexus-kyc-rekognition-lambda/requirements.txt" -t "${path.module}/build/kyc-rekognition" --quiet --no-warn-script-location --platform manylinux2014_x86_64 --implementation cp --python-version 3.12 --only-binary=:all:
      cp -r "${path.module}/../LAMBDA/nexus-kyc-rekognition-lambda/nexus-kyc-rekognition-lambda/src" "${path.module}/build/kyc-rekognition/src"
    EOT
  }
}

data "archive_file" "kyc_rekognition_zip" {
  type        = "zip"
  source_dir  = "${path.module}/build/kyc-rekognition"
  output_path = "${path.module}/build/kyc-rekognition.zip"
  depends_on  = [null_resource.kyc_rekognition_build]
}

# ── Lambda Function ───────────────────────────────────────────────────────────

resource "aws_lambda_function" "kyc_rekognition" {
  function_name = "nexus-josue-kyc-rekognition-lambda"
  role          = aws_iam_role.kyc_rekognition_lambda.arn
  handler       = "src.handler.process_kyc_document"
  runtime       = "python3.12"
  memory_size   = 256
  timeout       = 30

  filename         = data.archive_file.kyc_rekognition_zip.output_path
  source_code_hash = data.archive_file.kyc_rekognition_zip.output_base64sha256

  ephemeral_storage {
    size = 512
  }

  tracing_config {
    mode = "Active"
  }

  environment {
    variables = {
      AWS_REGION_NAME             = data.aws_region.current.name
      KYC_DOCUMENTS_BUCKET        = aws_s3_bucket.kyc_documents.bucket
      RESULTS_QUEUE_URL           = aws_sqs_queue.kyc_rekognition_results.url
      SUPPORTED_CONTENT_TYPES     = "image/jpeg,image/png,image/webp"
      MAX_DOCUMENT_SIZE_BYTES     = "10485760"
      MIN_DOCUMENT_SIZE_BYTES     = "10000"
      MIN_TEXT_CONFIDENCE         = "70"
      MIN_TEXT_ELEMENT_COUNT      = "5"
      MIN_FACE_QUALITY_BRIGHTNESS = "40"
      MIN_FACE_QUALITY_SHARPNESS  = "40"
      LOG_LEVEL                   = "INFO"
    }
  }
}

# ── S3 Trigger ────────────────────────────────────────────────────────────────

resource "aws_lambda_permission" "kyc_rekognition_s3" {
  statement_id  = "AllowS3Invoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.kyc_rekognition.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.kyc_documents.arn
}

resource "aws_s3_bucket_notification" "kyc_documents" {
  bucket = aws_s3_bucket.kyc_documents.id

  dynamic "lambda_function" {
    for_each = ["jpg", "png", "webp"]
    content {
      lambda_function_arn = aws_lambda_function.kyc_rekognition.arn
      events              = ["s3:ObjectCreated:*"]
      filter_prefix       = "kyc/"
      filter_suffix       = ".${lambda_function.value}"
    }
  }

  depends_on = [aws_lambda_permission.kyc_rekognition_s3]
}

# ── CloudWatch Alarms ─────────────────────────────────────────────────────────

resource "aws_cloudwatch_metric_alarm" "kyc_high_rejection_rate" {
  alarm_name          = "nexus-josue-kyc-high-rejection-rate"
  alarm_description   = "More than 40% of KYC documents failing quality gates. May indicate a user flow issue (wrong side photographed, etc.)"
  namespace           = "Nexus/KycRekognition"
  metric_name         = "QualityGatePassed"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 0.6
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "kyc_results_queue_depth" {
  alarm_name          = "nexus-josue-kyc-results-queue-depth"
  alarm_description   = "KYC results not being consumed. nexus-ai-kyc-service may be down."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.kyc_rekognition_results.name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 50
  comparison_operator = "GreaterThanThreshold"
}

# ── Outputs ───────────────────────────────────────────────────────────────────

output "kyc_rekognition_lambda_arn" {
  value = aws_lambda_function.kyc_rekognition.arn
}

output "kyc_rekognition_results_queue_url" {
  value = aws_sqs_queue.kyc_rekognition_results.url
}

output "kyc_rekognition_results_queue_arn" {
  value = aws_sqs_queue.kyc_rekognition_results.arn
}
