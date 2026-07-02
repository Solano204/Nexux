---
name: migrate
description: Run Flyway database migrations for all NEXUS services or a specific service
---

Run Flyway database migrations for NEXUS services.

Usage examples the user might type:
- `/migrate` — migrate all services
- `/migrate nexus-fraud-service` — migrate one service
- `/migrate nexus-identity-service nexus-account-service` — migrate specific services

Steps:
1. If a service name was provided, migrate only that service. Otherwise migrate all.

2. Find migration files:
```bash
find <service>/src/main/resources -name "V*.sql" | sort
```

3. Check if the service is running (migrations can run against the live DB):
```bash
docker ps --filter name=nexus-postgres --format "{{.Status}}"
```
   If postgres is not running, STOP and tell the user to start it first.

4. For Spring Boot services, trigger Flyway via Maven:
```bash
mvn flyway:migrate -pl <service> -Dflyway.url=jdbc:postgresql://localhost:5434/<db_name> -Dflyway.user=nexus -Dflyway.password=<POSTGRES_PASSWORD>
```

5. Database name mapping:
   - nexus-identity-service → nexus_identity
   - nexus-account-service → nexus_accounts
   - nexus-transaction-service → nexus_transactions
   - nexus-fraud-service → nexus_fraud
   - nexus-ledger-service → nexus_ledger
   - nexus-saga-orchestrator → nexus_saga
   - nexus-risk-scoring-service → nexus_risk
   - nexus-ai-kyc-service → nexus_kyc
   - nexus-audit-query-jvm → nexus_audit
   - nexus-ai-assistant-service → nexus_ai_assistant

6. Report: which migrations ran, which were already applied, any errors.

Notes:
- Read POSTGRES_PASSWORD from `.env` before running (never hardcode it)
- Migration files are in `<service>/src/main/resources/db/migration/`
