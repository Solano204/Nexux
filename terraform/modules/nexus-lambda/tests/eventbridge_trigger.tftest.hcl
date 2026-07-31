variables {
  function_name = "test-scheduled-lambda"
  handler       = "com.test.Handler::handleRequest"
  jar_path      = "./tests/fixtures/dummy.jar"
  source_hash   = "dGVzdA=="
  eventbridge_trigger = {
    schedule_expression = "rate(5 minutes)"
  }
}

run "creates_eventbridge_rule_with_correct_schedule" {
  command = plan

  assert {
    condition     = aws_cloudwatch_event_rule.eventbridge[0].schedule_expression == "rate(5 minutes)"
    error_message = "El schedule_expression pasado como variable debe llegar intacto al event rule"
  }

  assert {
    condition     = length(aws_lambda_event_source_mapping.sqs) == 0
    error_message = "No debe crear event source mapping de SQS cuando el trigger es EventBridge"
  }
}

run "lambda_permission_grants_events_principal" {
  command = plan

  assert {
    condition     = aws_lambda_permission.eventbridge[0].principal == "events.amazonaws.com"
    error_message = "El permission del trigger EventBridge debe otorgar a events.amazonaws.com, no a otro principal"
  }
}
