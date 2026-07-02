# nexus-risk-scoring-service — Complete Dependency & Run Guide

## ⚠️ READ BUGS.md FIRST

Two bugs — fix Bug 1 before attempting to start the service.

---

## 1. What was created

| Item | Status |
|------|--------|
| `Dockerfile` | **REPLACED** — added `-XX:+ZGenerational` |
| `logback-spring.xml` | **NEW from scratch** — service had no logback config |
| `.github/workflows/nexus-risk-scoring-service.yml` | **NEW** — no CI existed |
| `application-dev.yml` | **NEW** — was missing |
| `application-production.yml` | **NEW** — was missing |
| `scripts/postgres-init.sql` | **NEW** |

---

## 2. What this service does

**Port 8094.** Deep financial intelligence — the platform's risk engine.

**Two computation modes:**

**Nightly batch** — `NightlyRiskScoringJobTriggerService` fires at 2:00 AM Mexico City via `@Scheduled`. Uses Virtual Thread parallelism to score all users concurrently. Each user goes through the full Plan-then-Act agent.

**Event-triggered** — `BehaviorEventConsumer` consumes `user.behavior.aggregated` Kafka topic. When a user's behavioral profile changes significantly (large transaction, new merchant category, location anomaly), risk score is recomputed immediately.

**Plan-then-Act agent** — `RiskScoringAgent` with 8 tools runs in a deterministic loop (temperature 0.0, GPT-4o). The agent creates a `RiskScoringPlan` first, then executes tools in the planned order.

**8 tools:**

| Tool | Data source |
|------|------------|
| `AccountAgeTool` | PostgreSQL (identity service) |
| `TransactionHistoryTool` | Elasticsearch |
| `SpendingPatternTool` | Elasticsearch + analytics DynamoDB (via bridge) |
| `IncomeAnalysisTool` | Elasticsearch |
| `CounterpartyAnalysisTool` | Elasticsearch |
| `KycStatusTool` | nexus-ai-kyc-service |
| `GeographicRiskTool` | Elasticsearch + geographic risk database |
| `ExternalCreditTool` | External credit bureau API |

**Output:** `RiskProfile` with four sub-scores: `CreditRiskScore`, `BehavioralRiskScore`, `ComplianceRiskScore`, `VelocityRiskProfile`. Stored in PostgreSQL + cached in Redis.

---

## 3. Runtime dependencies

| Service | Port | Why |
|---------|------|-----|
| **PostgreSQL 16** | 5432 | `risk_profiles` + `computation_jobs` tables |
| **Redis 7** | 6379 | Cached scores consumed by fraud service + AI assistant |
| **Elasticsearch 8.13** | 9200 | Transaction history for all 8 tools |
| **Kafka** | 9092 | `user.behavior.aggregated` consumer + `risk.events` producer |
| **nexus-config-service** | 8888 | `fail-fast: true` |
| **nexus-discovery-service** | 8761 | Service registration |

| External | What breaks |
|---------|------------|
| **OpenAI API (gpt-4o)** | Risk scoring fails — no fallback. Agent cannot run without an LLM. |

---

## 4. Missing dependency — add to pom.xml

```xml
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>1.5.2</version>
</dependency>
```

---

## 5. Important: pom.xml self-documents its own fixes

The `pom.xml` has `<!-- ✅ FIXED: ... -->` comments explaining changes already made to the POM before this review. These include:
- Spring AI version override removed (was accidentally set to GA 1.0.0, now inherits 1.0.0-M6 from root)
- Standalone `<dependencyManagement>` block added (module not yet in root `<modules>`)
- Lombok annotation processor version pinned for standalone builds

These fixes are already in place — no action required.

---

## 6. Rate limiter

Resilience4j `openai-risk` rate limiter: **55 calls/minute** (safely below OpenAI's 60 RPM limit). During nightly batch with many users, the batch processor respects this limit — it queues Virtual Thread tasks but each waits up to 30s for a rate limit slot before failing.

If the batch takes too long, consider: raising the rate limit tier on your OpenAI account, or processing users in smaller batches with a longer window.

---

## 7. How to run

```bash
# 1. Fix Main.java (see BUGS.md)
# 2. Build
mvn package -DskipTests
# 3. Start
docker compose up -d
curl http://localhost:8094/actuator/health
# 4. Verify nightly scheduler registered
curl http://localhost:8094/actuator/scheduledtasks
```
