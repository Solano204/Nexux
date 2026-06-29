# 15 — nexus-audit-query-jvm
**Port:** 8097 | **Gateway base:** http://localhost:8080

## External Dependencies
- Elasticsearch (port 9201) — reads `nexus-audit-*` written by audit-write-native
- MongoDB (port 27018) — compliance reports storage
- OpenAI API — natural language compliance query only

## Event Flow
Read-only service. No Kafka events published or consumed.
Data source: Elasticsearch index `nexus-audit-*` (written by `audit-write-native`).

## Note on roles
Endpoints marked [COMPLIANCE_OFFICER] need that role in JWT. Regular users get 403.

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8097/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

---

### PUBLIC ENDPOINTS

### 2. Platform audit statistics
```
GET http://localhost:8080/api/v1/audit/platform/statistics
```
Expected: 200 — { totalAuditEvents: N, status: "OPERATIONAL" }
No auth needed. Good first check to confirm Elasticsearch has data.

> **Kafka topics:** none
> **DB affected:**
> - Elasticsearch `nexus-audit-*` — count aggregate query across all documents

### 3. Get audit events for a user
```
GET http://localhost:8080/api/v1/audit/users/{userId}/events?page=0&size=50
Authorization: Bearer {accessToken}
```
Optional params: startDate, endDate (ISO-8601), severity

Expected: 200 — paginated audit events

> **Kafka topics:** none
> **DB affected:**
> - Elasticsearch `nexus-audit-*` — bool query: filter userId + optional date range + severity, paginated with search_after

### 4. Get transaction audit trace
```
GET http://localhost:8080/api/v1/audit/transactions/{transactionId}/trace
Authorization: Bearer {accessToken}
```
Expected: 200 — cross-service trace of all events for that transaction

> **Kafka topics:** none
> **DB affected:**
> - Elasticsearch `nexus-audit-*` — multi-query across all index patterns WHERE transactionId, sorted by @timestamp ASC
> Pulls events from identity, fraud, transaction, ledger, saga, and account in one cross-service trace.

---

### COMPLIANCE OFFICER ENDPOINTS [COMPLIANCE_OFFICER role required]

### 5. User timeline (compliance view)
```
GET http://localhost:8080/api/v1/audit/users/{userId}/timeline?page=0&size=50
Authorization: Bearer {accessToken}
```
Expected: 200 or 403

> **Kafka topics:** none
> **DB affected:**
> - Elasticsearch `nexus-audit-*` — all event types for userId across all services, sorted chronologically

### 6. Get compliance alerts
```
GET http://localhost:8080/api/v1/audit/compliance/alerts?severity=WARNING,CRITICAL&page=0&size=20
Authorization: Bearer {accessToken}
```
Expected: 200 or 403

> **Kafka topics:** none
> **DB affected:**
> - Elasticsearch `nexus-audit-*` — filter WHERE severity IN [WARNING, CRITICAL] AND event_type MATCHES compliance patterns

### 7. Get compliance reports
```
GET http://localhost:8080/api/v1/audit/compliance/reports?page=0&size=20
Authorization: Bearer {accessToken}
```
Expected: 200 or 403

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_audit.compliance_reports` — find paginated, sorted by generated_at DESC

### 8. Natural language compliance query (needs OpenAI + COMPLIANCE_OFFICER)
```
POST http://localhost:8080/api/v1/audit/compliance/query
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "naturalLanguageQuery": "Show me all failed transactions for user {userId} in the last 30 days",
  "targetUserId": "{userId}",
  "startDate": "2026-05-21",
  "endDate": "2026-06-21",
  "queryType": "SUSPICIOUS_ACTIVITY"
}
```
Expected: 200 — { summary, findings, citations, riskLevel }
queryType: SUSPICIOUS_ACTIVITY, TRANSACTION_HISTORY, USER_BEHAVIOR, COMPLIANCE_CHECK

> **Kafka topics:** none
> **DB affected:**
> - Elasticsearch `nexus-audit-*` — structured query from date/userId params to fetch raw audit documents
> - OpenAI API — POST /v1/chat/completions: interprets NL query, analyzes retrieved docs, returns JSON summary + findings + citations
> - MongoDB `nexus_audit.compliance_reports` — INSERT generated report (so it appears in endpoint #7)
