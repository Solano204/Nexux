# ── nexus-auth-lambda ─────────────────────────────────────────────────────────
# Terraform port of LAMBDA/nexus-auth-lambda/nexus-auth-lambda/template.yaml
# Cognito JWT validation + session management. SnapStart for sub-500ms cold starts.
# ─────────────────────────────────────────────────────────────────────────────

variable "auth_lambda_jar_path" {
  description = "Path to the built shaded jar. Built automatically by null_resource.auth_lambda_build on apply."
  type        = string
  default     = "../LAMBDA/nexus-auth-lambda/nexus-auth-lambda/target/nexus-auth-lambda-1.0.0.jar"
}

# Builds the jar automatically on `terraform apply` — no manual `mvn package` step.
resource "null_resource" "auth_lambda_build" {
  triggers = {
    pom_hash = filesha256("../LAMBDA/nexus-auth-lambda/nexus-auth-lambda/pom.xml")
    source_hash = sha1(join("", [
      for f in sort(fileset("../LAMBDA/nexus-auth-lambda/nexus-auth-lambda/src/main", "**")) :
      filesha1("../LAMBDA/nexus-auth-lambda/nexus-auth-lambda/src/main/${f}")
    ]))
  }

  provisioner "local-exec" {
    interpreter = ["bash", "-c"]
    command     = "JAVA_HOME='/c/Program Files/Java/jdk-21' mvn -f ../LAMBDA/nexus-auth-lambda/nexus-auth-lambda/pom.xml clean package -DskipTests -q"
  }
}

data "local_file" "auth_lambda_jar" {
  filename   = var.auth_lambda_jar_path
  depends_on = [null_resource.auth_lambda_build]
}

variable "local_plane_identity_url" {
  description = <<-EOT
    URL of the local plane nexus-identity-service.
    host.docker.internal only resolves for SAM-local/same-host testing.
    For real AWS Lambda reaching your docker-compose stack, override with a
    publicly reachable tunnel URL (ngrok / Cloudflare Tunnel / Tailscale Funnel).
  EOT
  type        = string
  default     = "http://host.docker.internal:8083"
}

# ── DynamoDB Tables ───────────────────────────────────────────────────────────

resource "aws_dynamodb_table" "sessions" {
  name         = "nexus-josue-sessions"
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
  attribute {
    name = "GSI_JTI"
    type = "S"
  }
  attribute {
    name = "cognitoSub"
    type = "S"
  }

  global_secondary_index {
    name            = "JtiIndex"
    hash_key        = "GSI_JTI"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = "CognitoSubIndex"
    hash_key        = "cognitoSub"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }
}

resource "aws_dynamodb_table" "revoked_tokens" {
  name         = "nexus-josue-revoked-tokens"
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

# ── Cognito User Pool ─────────────────────────────────────────────────────────

resource "aws_cognito_user_pool" "nexus" {
  name = "nexus-josue-user-pool"

  # Simplified from the SAM template's MfaConfiguration: OPTIONAL.
  # OPTIONAL MFA requires wiring software_token_mfa_configuration (TOTP) or
  # sms_configuration (SNS origination) — out of scope for this port. Off for now.
  mfa_configuration = "OFF"

  password_policy {
    minimum_length    = 12
    require_uppercase = true
    require_lowercase = true
    require_numbers   = true
    require_symbols   = true
  }

  schema {
    name                     = "userId"
    attribute_data_type      = "String"
    developer_only_attribute = false
    mutable                  = true
    required                 = false

    # AWS always stores an (empty) string_attribute_constraints block for
    # String-type custom attributes — must declare it explicitly or every
    # plan shows a perpetual diff. Cognito schema is immutable post-creation,
    # so an undeclared block here would error on apply, not just no-op.
    string_attribute_constraints {}
  }

  schema {
    name                     = "kycVerified"
    attribute_data_type      = "Boolean"
    developer_only_attribute = false
    mutable                  = true
    required                 = false
  }

  schema {
    name                     = "accountStatus"
    attribute_data_type      = "String"
    developer_only_attribute = false
    mutable                  = true
    required                 = false

    string_attribute_constraints {}
  }
}

resource "aws_cognito_user_pool_client" "mobile" {
  name         = "nexus-josue-mobile-client"
  user_pool_id = aws_cognito_user_pool.nexus.id

  generate_secret = false

  explicit_auth_flows = [
    "ALLOW_REFRESH_TOKEN_AUTH",
    "ALLOW_USER_SRP_AUTH",
    "ALLOW_USER_PASSWORD_AUTH",
  ]

  access_token_validity  = 1
  refresh_token_validity = 30
  id_token_validity      = 1

  token_validity_units {
    access_token  = "hours"
    refresh_token = "days"
    id_token      = "hours"
  }
}

# ── IAM Role ───────────────────────────────────────────────────────────────────

resource "aws_iam_role" "auth_lambda" {
  name = "nexus-josue-auth-lambda-role-${var.environment}"

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

resource "aws_iam_role_policy_attachment" "auth_lambda_basic" {
  role       = aws_iam_role.auth_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "auth_lambda_xray" {
  role       = aws_iam_role.auth_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess"
}

resource "aws_iam_role_policy" "auth_lambda" {
  name = "nexus-josue-auth-lambda-policy-${var.environment}"
  role = aws_iam_role.auth_lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SessionTablesCrud"
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query",
          "dynamodb:Scan",
          "dynamodb:BatchGetItem",
          "dynamodb:BatchWriteItem"
        ]
        Resource = [
          aws_dynamodb_table.sessions.arn,
          "${aws_dynamodb_table.sessions.arn}/index/*",
          aws_dynamodb_table.revoked_tokens.arn,
          "${aws_dynamodb_table.revoked_tokens.arn}/index/*"
        ]
      },
      {
        Sid      = "PlaneBridgeSecretRead"
        Effect   = "Allow"
        Action   = "secretsmanager:GetSecretValue"
        Resource = "arn:aws:secretsmanager:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:secret:nexus/*"
      }
    ]
  })
}

# ── Lambda Function ───────────────────────────────────────────────────────────

resource "aws_lambda_function" "auth" {
  function_name = "nexus-josue-auth-lambda"
  role          = aws_iam_role.auth_lambda.arn
  handler       = "com.nexus.auth.lambda.AuthLambdaHandler::handleRequest"
  runtime       = "java21"
  memory_size   = 512
  timeout       = 29

  filename         = data.local_file.auth_lambda_jar.filename
  source_code_hash = data.local_file.auth_lambda_jar.content_base64sha256

  publish = true

  tracing_config {
    mode = "Active"
  }

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      # NOTE: AWS_REGION is a reserved Lambda runtime env var — use AWS_REGION_NAME instead.
      AWS_REGION_NAME               = data.aws_region.current.name
      COGNITO_USER_POOL_ID          = aws_cognito_user_pool.nexus.id
      COGNITO_CLIENT_ID             = aws_cognito_user_pool_client.mobile.id
      DYNAMODB_SESSIONS_TABLE       = aws_dynamodb_table.sessions.name
      DYNAMODB_REVOKED_TOKENS_TABLE = aws_dynamodb_table.revoked_tokens.name
      LOCAL_PLANE_IDENTITY_URL      = var.local_plane_identity_url
      PLANE_BRIDGE_SECRET           = local.plane_bridge_secret_value
    }
  }
}

resource "aws_lambda_alias" "auth_live" {
  name             = "live"
  function_name    = aws_lambda_function.auth.function_name
  function_version = aws_lambda_function.auth.version
}

# ── HTTP API Gateway ──────────────────────────────────────────────────────────

resource "aws_apigatewayv2_api" "auth" {
  name          = "nexus-josue-auth-http-api"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_integration" "auth" {
  api_id                 = aws_apigatewayv2_api.auth.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_alias.auth_live.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "validate_token" {
  api_id    = aws_apigatewayv2_api.auth.id
  route_key = "POST /auth/validate"
  target    = "integrations/${aws_apigatewayv2_integration.auth.id}"
}

resource "aws_apigatewayv2_route" "refresh_token" {
  api_id    = aws_apigatewayv2_api.auth.id
  route_key = "POST /auth/refresh"
  target    = "integrations/${aws_apigatewayv2_integration.auth.id}"
}

resource "aws_apigatewayv2_route" "extend_session" {
  api_id    = aws_apigatewayv2_api.auth.id
  route_key = "POST /auth/extend-session"
  target    = "integrations/${aws_apigatewayv2_integration.auth.id}"
}

resource "aws_apigatewayv2_route" "revoke_token" {
  api_id    = aws_apigatewayv2_api.auth.id
  route_key = "POST /auth/revoke"
  target    = "integrations/${aws_apigatewayv2_integration.auth.id}"
}

resource "aws_apigatewayv2_route" "kyc_status" {
  api_id    = aws_apigatewayv2_api.auth.id
  route_key = "GET /auth/kyc-status/{userId}"
  target    = "integrations/${aws_apigatewayv2_integration.auth.id}"
}

resource "aws_apigatewayv2_stage" "auth_default" {
  api_id      = aws_apigatewayv2_api.auth.id
  name        = "$default"
  auto_deploy = true
}

resource "aws_lambda_permission" "auth_apigw" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.auth.function_name
  qualifier     = aws_lambda_alias.auth_live.name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.auth.execution_arn}/*/*"
}

# ── Outputs ────────────────────────────────────────────────────────────────────

output "auth_lambda_arn" {
  value = aws_lambda_function.auth.arn
}

output "auth_lambda_api_url" {
  value = aws_apigatewayv2_stage.auth_default.invoke_url
}

output "cognito_user_pool_id" {
  value = aws_cognito_user_pool.nexus.id
}

output "cognito_user_pool_client_id" {
  value = aws_cognito_user_pool_client.mobile.id
}

output "sessions_table_name" {
  value = aws_dynamodb_table.sessions.name
}

output "revoked_tokens_table_name" {
  value = aws_dynamodb_table.revoked_tokens.name
}
