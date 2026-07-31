variables {
  function_name = "test-sqs-lambda"
  handler       = "com.test.Handler::handleRequest"
  jar_path      = "./tests/fixtures/dummy.jar"
  source_hash   = "dGVzdA=="
  sqs_trigger = {
    queue_arn = "arn:aws:sqs:us-east-1:123456789012:test-queue"
  }
}

run "creates_exactly_one_sqs_event_source_mapping" {
  command = plan

  assert {
    condition     = length(aws_lambda_event_source_mapping.sqs) == 1
    error_message = "Debe crear exactamente 1 event source mapping cuando sqs_trigger está seteado"
  }

  assert {
    condition     = length(aws_cloudwatch_event_rule.eventbridge) == 0
    error_message = "No debe crear recursos de EventBridge cuando el trigger es SQS"
  }

  assert {
    condition     = length(aws_s3_bucket_notification.this) == 0
    error_message = "No debe crear notification de S3 cuando el trigger es SQS"
  }
}

run "iam_role_has_basic_execution_and_xray_by_default" {
  command = plan

  assert {
    condition     = aws_iam_role_policy_attachment.basic_execution.policy_arn == "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
    error_message = "Todo Lambda debe tener CloudWatch Logs vía AWSLambdaBasicExecutionRole"
  }

  assert {
    condition     = length(aws_iam_role_policy_attachment.xray) == 1
    error_message = "enable_xray default=true debe attachear la policy de X-Ray"
  }
}
