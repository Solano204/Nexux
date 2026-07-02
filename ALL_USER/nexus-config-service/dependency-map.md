# nexus-config-service — Complete Dependency Map

## Port: 8888  |  Must start FIRST before any other service

---

## Infrastructure REQUIRES (direct)

| Component         | Port     | Why                                                                    |
|-------------------|----------|------------------------------------------------------------------------|
| Kafka             | 19092    | Spring Cloud Bus — publishes RefreshRemoteApplicationEvent on /busrefresh |
| GitHub            | HTTPS    | Git backend — reads config files from nexus-platform-config repo       |
| Discovery service | 8761     | Eureka registration (register-with-eureka: false — it does NOT register itself) |

**NO PostgreSQL, NO MongoDB, NO Redis, NO Elasticsearch, NO S3, NO SQS, NO OpenAI**

---

## What config service does

All 14 other services call config service at startup:
```
Service starts → GET http://localhost:8888/nexus-{service-name}/dev
              → config server fetches from GitHub repo (nexus-platform-config)
              → returns merged config: application-dev.yml + nexus-{service}-dev.yml
              → service applies config and continues startup
```

---

## Config files served (nexus-platform-config repo)

| File                                        | Overrides for                    |
|---------------------------------------------|----------------------------------|
| application-dev.yml                         | global dev defaults (all services)|
| nexus-identity-service-dev.yml             | identity-service                 |
| nexus-account-service-dev.yml              | account-service                  |
| nexus-transaction-service-dev.yml          | transaction-service              |
| nexus-fraud-service-dev.yml                | fraud-service                    |
| nexus-ledger-service-dev.yml               | ledger-service                   |
| nexus-notification-service-dev.yml         | notification-service             |
| nexus-analytics-service-dev.yml            | analytics-service                |
| nexus-risk-scoring-service-dev.yml         | risk-scoring-service             |
| nexus-saga-orchestrator-dev.yml            | saga-orchestrator                |
| nexus-ai-kyc-service-dev.yml               | ai-kyc-service                   |
| nexus-ai-assistant-service-dev.yml         | ai-assistant-service             |
| nexus-audit-query-jvm-dev.yml              | audit-query-jvm                  |
| nexus-api-gateway-dev.yml                  | api-gateway (if used)            |

---

## Endpoints

| Method | Path                    | Auth     | What it does                                  |
|--------|-------------------------|----------|-----------------------------------------------|
| GET    | /actuator/health        | none     | health check                                  |
| POST   | /actuator/busrefresh    | basic    | trigger refresh on ALL services via Kafka Bus |
| POST   | /monitor                | none     | GitHub webhook endpoint (triggers busrefresh) |
| GET    | /actuator/env           | basic    | view resolved environment                     |
| GET    | /actuator/prometheus    | none     | Prometheus metrics                            |

Basic auth credentials: `nexus-config` / `nexus-config-password` (or from CONFIG_SERVER_PASSWORD env)

---

## GitHub webhook setup (for auto-refresh on push)

```
URL:    http://{your-public-ip}:8888/monitor
Method: POST
Content-Type: application/json
Events: push
```
When GitHub pushes → /monitor receives payload → config server publishes to springCloudBus → all services auto-refresh @RefreshScope beans.

---

## Auto-refresh limitation

| Bean type                 | Refreshable without restart? |
|---------------------------|------------------------------|
| @RefreshScope beans       | YES — via /actuator/busrefresh |
| @Value in @RefreshScope   | YES                          |
| DataSource / Redis connection pools | NO — requires restart |
| Kafka bootstrap-servers   | NO — requires restart        |

---

## Notes

- refresh-rate: 30s — config server polls GitHub every 30 seconds for changes
- force-pull: true — always overwrites local git clone with remote
- GIT_TOKEN env var required — config server cannot clone private repo without it
- spring-cloud-config-monitor dependency added — enables /monitor endpoint for GitHub webhooks
