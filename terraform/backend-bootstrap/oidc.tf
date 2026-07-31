# GitHub Actions OIDC provider + role for CI/CD (05_TERRAFORM_AWS_CHANGES.md
# Section 7) - lives here for the same reason the state bucket does: it
# needs to exist before the CI workflow that assumes this role can run at
# all. Not applied in this session - review the plan, then apply yourself.

resource "aws_iam_openid_connect_provider" "github_actions" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"] # GitHub's known public thumbprint, not a secret
}

variable "github_repo" {
  description = "owner/repo that's allowed to assume the CI role, e.g. carlosjosue/NEXUS"
  type        = string
}

resource "aws_iam_role" "terraform_ci" {
  name = "nexus-josue-terraform-ci"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github_actions.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:*"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "terraform_ci_admin" {
  role = aws_iam_role.terraform_ci.name
  # AdministratorAccess is broad on purpose for a single developer with one
  # AWS account. The first real scope-down, if this ever needs it: a policy
  # limited to the services terraform/ actually manages (lambda, iam,
  # dynamodb, sqs, sns, s3, secretsmanager, cognito, apigateway, kms,
  # events, logs, cloudwatch) - not before there's a second person/team
  # touching this.
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

output "terraform_ci_role_arn" {
  value = aws_iam_role.terraform_ci.arn
}
