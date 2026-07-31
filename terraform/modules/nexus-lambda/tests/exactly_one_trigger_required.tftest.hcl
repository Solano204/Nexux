# Caso negativo: 0 triggers debe fallar el plan, no aplicar silenciosamente
# sin ningún trigger configurado.

variables {
  function_name = "test-no-trigger-lambda"
  handler       = "com.test.Handler::handleRequest"
  jar_path      = "./tests/fixtures/dummy.jar"
  source_hash   = "dGVzdA=="
  # Ningún *_trigger seteado - todos quedan en su default null
}

run "fails_plan_when_no_trigger_configured" {
  command = plan
  expect_failures = [check.exactly_one_trigger]
}
