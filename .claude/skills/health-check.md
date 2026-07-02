---
name: health-check
description: Check the health of all 16 running NEXUS services and report status in a table
---

Check health of all NEXUS platform services.

Steps:
1. Hit each service's health endpoint. Use the ports from the table below.
2. For each service, run:
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:<PORT>/actuator/health
```
   For audit-write-native (Quarkus):
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8096/q/health
```
3. Categorize results:
   - 200 = UP
   - Connection refused / timeout = DOWN
   - Other = DEGRADED

4. Print a status table:

| Service | Port | Status |
|---|---|---|
| nexus-api-gateway | 8080 | ? |
| nexus-config-service | 8888 | ? |
| nexus-discovery-service | 8761 | ? |
| nexus-identity-service | 8083 | ? |
| nexus-account-service | 8085 | ? |
| nexus-transaction-service | 8086 | ? |
| nexus-fraud-service | 8087 | ? |
| nexus-ledger-service | 8088 | ? |
| nexus-notification-service | 8089 | ? |
| nexus-ai-assistant-service | 8090 | ? |
| nexus-ai-kyc-service | 8091 | ? |
| nexus-analytics-service | 8092 | ? |
| nexus-risk-scoring-service | 8094 | ? |
| nexus-saga-orchestrator | 8095 | ? |
| nexus-audit-query-jvm | 8097 | ? |
| audit-write-native | 8096 | ? |

5. For any DOWN service, show the last 20 lines of its Docker logs:
```bash
docker logs --tail 20 nexus-<service-name>
```
6. Print a summary: X/16 services UP.
