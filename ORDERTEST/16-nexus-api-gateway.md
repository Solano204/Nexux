# 16 — nexus-api-gateway
**Port:** 8080 | **Start this LAST**

## External Dependencies
- All other services must be registered in Eureka before starting
- nexus-discovery-service must be UP

## Event Flow
No Kafka events, no DB. Pure HTTP routing layer.
Topics and DB tables affected by a gateway call depend entirely on which downstream service handles it.

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8080/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** none — gateway has no DB of its own

### 2. Verify routing to identity-service
```
GET http://localhost:8080/api/v1/auth/.well-known/jwks.json
```
Expected: 200 — JWKS JSON (routed to nexus-identity-service)

> **Kafka topics:** none
> **DB affected:** none — JWKS is in-memory on identity-service; gateway just forwards

### 3. Verify routing to discovery (via gateway)
Check Eureka registered routes — depends on gateway config.

> **Kafka topics:** none
> **DB affected:** none

---

## Gateway Routing Rules

| Path pattern | Routes to | Topics + DB → see that service's file |
|---|---|---|
| /api/v1/auth/** | nexus-identity-service | See `3-nexus-identity-service.md` |
| /api/v1/users/** | nexus-identity-service | See `3-nexus-identity-service.md` |
| /api/v1/accounts/** | nexus-account-service | See `4-nexus-account-service.md` |
| /api/v1/transactions/** | nexus-transaction-service | See `5-nexus-transaction-service.md` |
| /api/v1/ledger/** | nexus-ledger-service | See `7-nexus-ledger-service.md` |
| /api/v1/notifications/** | nexus-notification-service | See `8-nexus-notification-service.md` |
| /api/v1/kyc/** | nexus-ai-kyc-service | See `9-nexus-ai-kyc-service.md` |
| /api/v1/ai/** | nexus-ai-assistant-service | See `10-nexus-ai-assistant-service.md` |
| /api/v1/analytics/** | nexus-analytics-service | See `11-nexus-analytics-service.md` |
| /api/v1/audit/** | nexus-audit-query-jvm | See `15-nexus-audit-query-jvm.md` |
| /internal/v1/** | restricted to Docker internal network | — |

## Headers added by gateway before forwarding
- `X-User-Id` — extracted from validated JWT
- `X-Trace-Id` — distributed trace ID (also sent to Zipkin at localhost:9412)
- `X-Forwarded-For` — original client IP

## Common gateway errors
| Code | Reason |
|---|---|
| 401 | Missing or expired Bearer token |
| 403 | Token valid but accessing unauthorized resource |
| 502 | Downstream service is down / not registered in Eureka |
| 503 | No healthy instances of downstream service |
