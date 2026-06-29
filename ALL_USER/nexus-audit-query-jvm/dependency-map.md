# nexus-audit-query-jvm — Complete Dependency Map

## Port: 8097

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                             |
|-------------------|-------|-----------------------------------------------------------------|
| Elasticsearch     | 9202  | primary query source — searches nexus-audit-* indices          |
| MongoDB           | 27019 | nexus_audit — fallback / complementary audit document store     |
| Config service    | 8888  | loads config on startup                                         |
| Discovery service | 8761  | Eureka registration                                             |

**NO PostgreSQL, NO Redis, NO Kafka, NO S3, NO SQS, NO OpenAI**

---

## Kafka topics

None — this is a pure query service. It reads only. audit-write-native writes the data.

---

## Read-only relationship with audit-write-native

```
audit-write-native (8096) → writes to → MongoDB nexus_audit + Elasticsearch nexus-audit-*
audit-query-jvm    (8097) → reads from → MongoDB nexus_audit + Elasticsearch nexus-audit-*
```

These two services share the same stores but never communicate with each other directly.

---

## Public endpoints (require JWT via gateway)

| Method | Path                                          | Returns                                  |
|--------|-----------------------------------------------|------------------------------------------|
| GET    | /api/v1/audit/users/{userId}/events           | paginated audit events for a user        |
| GET    | /api/v1/audit/transactions/{transactionId}/trace | full audit trail for a transaction    |
| GET    | /api/v1/audit/platform/statistics             | platform-wide audit statistics           |
| POST   | /api/v1/audit/compliance/query                | flexible compliance query (body: filters)|
| GET    | /api/v1/audit/users/{userId}/timeline         | chronological user activity timeline     |
| GET    | /api/v1/audit/compliance/alerts               | compliance alerts list                   |
| GET    | /api/v1/audit/compliance/reports              | compliance reports                       |

---

## Elasticsearch query notes

- Index pattern: `nexus-audit-*` (monthly rotation, e.g. nexus-audit-2026-06)
- userId field is type `text` — use `userId.keyword` subfield for exact match queries
- Example: `{"query":{"term":{"userId.keyword":"e1be2ced-..."}}}`

---

## Gateway routes

Gateway has no route for /api/v1/audit/** in the dev config — hit port 8097 directly.
Check application.yml for prod route.

---

## Notes

- This service is read-only — it never writes anything
- All writes are done exclusively by audit-write-native (Quarkus)
- If Elasticsearch is down, queries fail — no fallback
- kafkaOffset field in audit documents shows -1 for some records (cosmetic, non-blocking)
