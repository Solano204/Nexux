# nexus-health-monitor-lambda — Complete Dependency & Run Guide

## No blocking bugs

The Lambda runs correctly as-is. See `BUGS.md` for two dead-code cleanup items worth addressing before the codebase grows.

---

## 1. What was missing / changed

| Item | Status |
|------|--------|
| `samconfig.toml` | **NEW** — was missing |
| `.github/workflows/health-monitor-pipeline.yml` | **IMPROVED** — added staging deploy, uses samconfig.toml `--config-env`, added PR trigger, preserved all existing design-invariant checks |

---

## 2. What this Lambda does

**Trigger:** CloudWatch Events, `rate(5 minutes)` — never HTTP, never SQS.  
**Runtime:** Python 3.12  
**No SnapStart** (Python — SnapStart is Java-only)

**Complete execution flow per invocation:**
1. Run health checks on all 15 services **in parallel** (ThreadPoolExecutor, 8 workers, ~8s wall time vs ~75s sequential)
2. Publish all results as CloudWatch metrics
3. For each unhealthy service: count consecutive failures → decide whether to alert → update CloudWatch alarm → send SNS
4. For each healthy service: check if it was previously failing → send recovery/all-clear SNS if so
5. Analyze platform-wide scenarios (cascade, infrastructure down, all-transaction-services-down)
6. Send scenario-level alerts for critical combinations
7. Maintain CloudWatch health dashboard (idempotent `put_dashboard`)

**State management:** CloudWatch metrics **are** the state — no DynamoDB, no Redis. Consecutive failure count is computed by reading the last N `ServiceHealthStatus` datapoints from CloudWatch history.

---

## 3. Services monitored (15 total)

| Service | Port | Criticality | Blocks transactions |
|---------|------|-------------|---------------------|
| nexus-api-gateway | 8080 | CRITICAL | Yes |
| nexus-identity-service | 8083 | CRITICAL | Yes |
| nexus-account-service | 8085 | CRITICAL | Yes |
| nexus-transaction-service | 8086 | CRITICAL | Yes |
| nexus-fraud-service | 8087 | HIGH | Yes |
| nexus-ledger-service | 8088 | HIGH | Yes |
| nexus-notification-service | 8089 | STANDARD | No |
| nexus-ai-assistant-service | 8090 | STANDARD | No |
| nexus-ai-kyc-service | 8091 | HIGH | Yes (onboarding) |
| nexus-analytics-service | 8092 | STANDARD | No |
| nexus-risk-scoring-service | 8094 | HIGH | No |
| nexus-saga-orchestrator | 8095 | CRITICAL | Yes |
| nexus-audit-query-jvm | 8097 | STANDARD | No |
| nexus-config-service | 8888 | INFRASTRUCTURE | No |
| nexus-discovery-service | 8761 | INFRASTRUCTURE | No |

**Alerting tiers:**
- `CRITICAL` → alert on **first** failure → page on-call
- `HIGH` → alert after **2** consecutive failures → ops team
- `STANDARD` → alert after N consecutive failures (configurable) → standard ops
- `INFRASTRUCTURE` → alert but no immediate page

---

## 4. Scenario analysis

The Lambda detects three platform-wide failure scenarios:

1. **Cascade failure** — 3+ CRITICAL services down simultaneously → likely infrastructure problem, not individual service bugs
2. **Infrastructure down** — Config Service OR Discovery Service down → all other services cannot register or fetch config
3. **All transaction services down** — every service with `blocks_transactions: True` is down → no financial transactions can process

Each scenario triggers a separate SNS alert with its own escalation path.

---

## 5. Monitor the monitor

The template creates `HealthMonitorLambdaErrors` — a CloudWatch alarm that watches the health monitor Lambda **itself**. If this Lambda errors, the entire platform is blind to health issues.

- Fires if: 2+ Lambda errors in 15 minutes (3 check cycles)
- Also watches: Lambda throttles
- Alerts to: `nexus-health-monitor-selfwatch` SNS topic
- Subscribe this topic to your on-call system separately from the health alerts

---

## 6. What you need installed

| Tool | Why |
|------|-----|
| Python 3.12 | Local dev |
| AWS SAM CLI | Build and deploy |
| Docker | LocalStack, `sam local invoke` |
| AWS CLI v2 | LocalStack verification |

---

## 7. Local development

```bash
# 1. Start LocalStack (SNS + CloudWatch + SecretsManager)
docker compose up -d nexus-localstack

# 2. Also start the local plane services (or some of them)
# The Lambda will check http://host.docker.internal:{port}/actuator/health

# 3. Invoke once manually
sam local invoke HealthMonitorLambda \
  -e events/manual-trigger.json \
  --env-vars events/env.json

# 4. Run tests
pip install -r requirements.txt pytest
pytest tests/unit/ -v
```

---

## 8. Deploy to AWS

```bash
sam build
sam deploy                       # dev
sam deploy --config-env staging  # staging
sam deploy --config-env prod \
  --parameter-overrides "LocalPlaneBaseUrl=http://your-server-ip"
```

`LocalPlaneBaseUrl` is the IP/hostname of the machine running the Docker Compose stack — i.e., the server where all 15 Spring Boot services are running.

---

## 9. Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID_STAGING` | IAM for staging |
| `AWS_SECRET_ACCESS_KEY_STAGING` | — |
| `AWS_ACCESS_KEY_ID_PROD` | IAM for production |
| `AWS_SECRET_ACCESS_KEY_PROD` | — |
| `LOCAL_PLANE_BASE_URL_STAGING` | e.g. `http://10.0.1.50` |
| `LOCAL_PLANE_BASE_URL_PROD` | IP of production local plane host |
