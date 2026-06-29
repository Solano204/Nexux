# nexus-analytics-service — Complete Dependency Map

## Port: 8092

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                               |
|-------------------|-------|-------------------------------------------------------------------|
| PostgreSQL        | 5433  | nexus_analytics DB — monthly summaries, spending categories       |
| Elasticsearch     | 9202  | real-time transaction search + aggregations                       |
| Redis             | 6380  | insights cache, rate limiting                                     |
| Kafka             | 19092 | Kafka Streams topology consumes transaction events                |
| OpenAI API        | HTTPS | InsightGenerationService generates AI-powered financial insights  |
| Config service    | 8888  | loads config on startup                                           |
| Discovery service | 8761  | Eureka registration                                               |

---

## Kafka topics CONSUMED (via Kafka Streams AnalyticsTopology)

| Topic                 | When produced by             | What analytics does with it          |
|-----------------------|------------------------------|--------------------------------------|
| transactions.completed | transaction-service         | build monthly summaries + merchant stats |
| transactions.failed   | transaction-service          | track failure rates                  |
| transactions.merchant-stats | transaction-service (Streams) | aggregate merchant spending      |

---

## Kafka topics PRODUCED

| Topic               | Consumed by       | When                             |
|---------------------|-------------------|----------------------------------|
| analytics.anomalies | audit-write-native | when spending anomaly detected  |

---

## Public endpoints (require JWT via gateway)

| Method | Path                                                    | Returns                              |
|--------|---------------------------------------------------------|--------------------------------------|
| GET    | /api/v1/analytics/accounts/{accountId}/monthly/{yearMonth} | monthly spending summary          |
| GET    | /api/v1/analytics/accounts/{accountId}/trends           | spending trends over time            |
| GET    | /api/v1/analytics/accounts/{accountId}/merchants        | top merchants by spend               |
| GET    | /api/v1/analytics/platform/realtime                     | platform-wide real-time metrics      |
| GET    | /api/v1/analytics/accounts/{accountId}/insights/{yearMonth} | AI-generated financial insights  |

---

## Internal endpoints

| Method | Path                                      | Who calls it        |
|--------|-------------------------------------------|---------------------|
| GET    | /internal/v1/streams/category-spending    | admin / monitoring  |
| GET    | /internal/v1/streams/health/lag           | monitoring          |

---

## Indirect dependencies

| Component | Role                                                          |
|-----------|---------------------------------------------------------------|
| MongoDB   | NOT used directly — audit-write writes analytics events       |
| S3 / SQS  | NOT used                                                      |

---

## Notes

- OpenAI key must be real for /insights endpoint to work — returns placeholder without it
- Elasticsearch port in config server file is 9200 but host port is 9202 — verify before testing search endpoints
- Gateway route exists for /api/v1/analytics/** with JwtAuthentication
- Gateway has no route for /internal/v1/streams/** — hit port 8092 directly
