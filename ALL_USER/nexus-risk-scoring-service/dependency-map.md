# nexus-risk-scoring-service — Complete Dependency Map

## Port: 8094

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                              |
|-------------------|-------|------------------------------------------------------------------|
| PostgreSQL        | 5433  | nexus_risk DB — risk_profiles, risk_history                     |
| Elasticsearch     | 9202  | risk profile indexing + aggregation queries                     |
| Redis             | 6380  | risk score cache, velocity data                                 |
| Kafka             | 19092 | consumes behavior events, produces risk scores                  |
| Config service    | 8888  | loads config on startup                                         |
| Discovery service | 8761  | Eureka registration                                             |

**NO MongoDB, NO S3, NO SQS, NO OpenAI**

---

## Kafka topics CONSUMED

| Topic                   | Group ID                      | Sent by           | What it does                      |
|-------------------------|-------------------------------|-------------------|-----------------------------------|
| user.behavior.aggregated | risk-scoring-behavior-events  | analytics-service | updates risk profile for user     |

---

## Kafka topics PRODUCED

| Topic       | Consumed by       | When                              |
|-------------|-------------------|-----------------------------------|
| risk.scored | fraud-service     | after risk score computed         |
| risk.alert  | notification-service, audit-write | when risk tier changes to HIGH/CRITICAL |

---

## All endpoints are internal (no public user-facing API)

| Method | Path                                         | Who calls it               |
|--------|----------------------------------------------|----------------------------|
| GET    | /internal/v1/risk/profiles/{userId}          | fraud-service, compliance  |
| GET    | /internal/v1/risk/profiles/{userId}/tier     | fraud-service (quick check)|
| GET    | /internal/v1/risk/profiles/{userId}/history  | compliance / admin         |
| POST   | /internal/v1/risk/profiles/{userId}/compute  | manual trigger / admin     |
| POST   | /internal/v1/risk/batch/trigger              | scheduled job / admin      |
| GET    | /internal/v1/risk/batch/status               | monitoring                 |
| GET    | /internal/v1/risk/stats                      | monitoring / dashboard     |

---

## Indirect dependencies

| Component | Role                                                              |
|-----------|-------------------------------------------------------------------|
| MongoDB   | NOT used directly                                                 |
| S3 / SQS  | NOT used                                                          |
| OpenAI    | NOT used                                                          |

---

## Notes

- Gateway has no route for /internal/v1/risk/** — hit port 8094 directly
- Risk tiers: LOW / MEDIUM / HIGH / CRITICAL
- Batch compute can be triggered manually for all users or single user
