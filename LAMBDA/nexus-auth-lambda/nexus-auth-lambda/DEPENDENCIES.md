# nexus-auth-lambda — Complete Dependency & Run Guide

## No source code bugs found

The Java source code is correct and complete. Two things were **missing from the repo**:

| Item | Status |
|------|--------|
| `samconfig.toml` | **NEW** — required for `sam deploy` without interactive prompts |
| `.github/workflows/nexus-auth-lambda.yml` | **NEW** — no CI existed |

---

## 1. What this Lambda does

**Trigger:** AWS HTTP API Gateway (API Gateway v2)  
**Runtime:** Java 21 with **SnapStart** (~200-500ms cold start)

Five HTTP endpoints:

| Method | Path | Handler |
|--------|------|---------|
| POST | `/auth/validate` | `TokenValidationHandler` — validates Cognito JWT, checks revocation list |
| POST | `/auth/refresh` | `TokenRefreshHandler` — exchanges refresh token for new access token |
| POST | `/auth/extend-session` | `SessionExtensionHandler` — extends TTL on active session |
| POST | `/auth/revoke` | `TokenRevocationHandler` — adds JTI to revoked tokens table |
| GET | `/auth/kyc-status/{userId}` | `KycStatusHandler` — bridges to local plane identity service |

**DynamoDB tables:**
- `nexus-sessions` — active sessions with JTI + CognitoSub GSIs (TTL: token expiry)
- `nexus-revoked-tokens` — invalidated JTIs (TTL: token expiry)

**Bridge:** `LocalPlaneBridgeClient` calls `nexus-identity-service` (Spring Boot, local Docker) for KYC status. The Lambda is in AWS; the identity service is on local infrastructure — the bridge uses a shared secret from Secrets Manager.

---

## 2. SnapStart — how it works

```
sam deploy
  → publishes new Lambda version (because AutoPublishAlias: live)
  → AWS initializes JVM: runs static {}, loads classes, downloads JWKS
  → CRaC beforeCheckpoint(): closes HTTP connections
  → AWS snapshots the initialized JVM memory
  → alias 'live' points to new version

On cold start:
  → AWS restores JVM from snapshot (~200ms vs 5-15s normal Java)
  → CRaC afterRestore(): re-opens connections, refreshes JWKS if stale
  → Handler ready
```

**What this means for you:** Every `sam deploy` triggers a new snapshot automatically. You don't need to do anything extra — just deploy normally.

---

## 3. What you need installed

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | **21** | Lambda Java 21 runtime + SnapStart |
| Maven | 3.9+ | Build fat JAR via shade plugin |
| AWS SAM CLI | any recent | Build and deploy |
| Docker Desktop | 24+ | `sam local start-api`, LocalStack |
| AWS CLI v2 | any | LocalStack verification |

---

## 4. Local development workflow

```bash
# 1. Start LocalStack (creates DynamoDB tables + Secrets Manager secret)
docker compose up -d nexus-localstack

# 2. Build fat JAR
cd nexus-auth-lambda
mvn package -DskipTests

# 3. Start local API endpoint (Lambda behind HTTP API)
sam local start-api --env-vars events/env.json
# Lambda available at http://localhost:3000

# 4. Test
curl -X POST http://localhost:3000/auth/validate \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat events/validate-event.json | jq -r '.headers.authorization | ltrimstr("Bearer ")')"

# Or use the sample event directly:
sam local invoke NexusAuthLambda \
  -e events/validate-event.json \
  --env-vars events/env.json
```

---

## 5. Deploy to AWS

```bash
# Create S3 buckets for SAM artifacts (one-time per environment)
aws s3 mb s3://nexus-sam-artifacts-dev
aws s3 mb s3://nexus-sam-artifacts-staging
aws s3 mb s3://nexus-sam-artifacts-prod

# Build
mvn package -DskipTests
sam build

# Deploy
sam deploy                       # dev (prompts for confirmation)
sam deploy --config-env staging  # staging
sam deploy --config-env prod     # production
```

---

## 6. Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID_STAGING` | IAM credentials for staging |
| `AWS_SECRET_ACCESS_KEY_STAGING` | — |
| `AWS_ACCESS_KEY_ID_PROD` | IAM credentials for production |
| `AWS_SECRET_ACCESS_KEY_PROD` | — |

IAM permissions needed: Lambda, DynamoDB, Cognito, CloudFormation, IAM, S3, Secrets Manager.

---

## 7. The LocalPlaneBridgeClient

The KYC status endpoint (`GET /auth/kyc-status/{userId}`) calls out to `nexus-identity-service` (the Spring Boot service running in Docker). This is the "local plane" — the Lambda in AWS bridges to infrastructure running on-premise/in Docker.

The bridge is authenticated with a shared secret stored in AWS Secrets Manager under `nexus-josue/plane-bridge-secret`. The `LocalPlaneBridgeClient` retrieves this on initialization (snapshot captures it) and re-fetches after SnapStart restore.

In `events/env.json` for local dev, `LOCAL_PLANE_IDENTITY_URL` points to `host.docker.internal:8083` — which resolves to the Spring Boot service running locally on your machine.

---

## 8. Monitoring

- X-Ray tracing: `Tracing: Active` in template.yaml — every invocation traced
- CloudWatch Logs: Lambda stdout/stderr captured automatically
- Key metrics to watch:
  - `Duration` (P99) — should be <100ms after SnapStart restore
  - `InitDuration` (cold starts) — should be <500ms with SnapStart
  - `Errors` — create alarm if > 0 in 60s window
