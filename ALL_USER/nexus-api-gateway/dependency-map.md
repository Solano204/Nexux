# nexus-api-gateway — Complete Dependency Map

## Port: 8080  |  Entry point for ALL external requests

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                                     |
|-------------------|-------|-------------------------------------------------------------------------|
| Redis             | 6380  | JWT blacklist check (ReactiveStringRedisTemplate) + rate limiting       |
| Kafka             | 19092 | Spring Cloud Bus (config refresh) — springCloudBus topic                |
| identity-service  | 8010  | fetches JWKS (public keys) for JWT signature validation                 |
| Discovery service | 8761  | Eureka — resolves lb:// URIs to actual service instances                |
| Config service    | 8888  | NOT used in dev (config.enabled=false in dev) — used in prod            |

**NO PostgreSQL, NO MongoDB, NO Elasticsearch, NO S3, NO SQS, NO OpenAI**

---

## What the gateway does on every authenticated request

```
1. RequestSanitizationFilter   — strip injected X-User-Id / X-User-Roles headers (security)
2. JwtAuthenticationFilter     — validate RS256 signature via JWKS from identity-service
3. JwtAuthenticationFilter     — check Redis blacklist (jwt:blacklist:{jti})
4. JwtAuthenticationFilter     — check accountStatus claim (SUSPENDED → 403)
5. JwtAuthenticationFilter     — inject X-User-Id, X-User-Roles, X-Request-Id, X-Token-Expires-At
6. RequestRateLimiter          — per-user or per-IP rate limiting via Redis
7. CircuitBreaker              — Resilience4j — open after 90% failure rate
8. Route to downstream service
```

---

## Route table (dev — application-dev.yml)

| Route ID                        | Path pattern                  | Auth     | Target port |
|---------------------------------|-------------------------------|----------|-------------|
| nexus-identity-service-public   | /api/v1/auth/**               | none     | 8010        |
| nexus-identity-service-users    | /api/v1/users/**              | JWT      | 8010        |
| nexus-identity-service-internal | /internal/v1/users/**, /internal/v1/health/** | none | 8010  |
| nexus-account-service           | /api/v1/accounts/**           | JWT      | 8085        |
| nexus-transaction-service       | /api/v1/transactions/**       | JWT      | 8086        |
| nexus-fraud-service-internal    | /internal/v1/fraud/**         | JWT (wrong — should be none) | 8087 |
| nexus-ledger-service            | /api/v1/ledger/**             | JWT      | 8088        |
| nexus-analytics-service         | /api/v1/analytics/**          | JWT      | 8092        |
| nexus-risk-scoring-service      | /api/v1/risk/**               | JWT      | 8094        |
| nexus-ai-kyc-service            | /api/v1/kyc/**                | JWT      | 8091        |
| nexus-ai-assistant-service      | /ai/**  (StripPrefix=1)       | JWT      | 8090        |
| actuator-internal               | /actuator/**                  | none     | 8080 (self) |

---

## Routes MISSING in dev config (hit services directly)

| Missing route                  | Service port | Internal path                     |
|-------------------------------|-------------|-----------------------------------|
| /internal/v1/transactions/**  | 8086        | transaction-service internal      |
| /internal/v1/sagas/**         | 8095        | saga-orchestrator internal        |
| /internal/v1/ledger/**        | 8088        | ledger-service internal           |
| /internal/v1/kyc/**           | 8091        | ai-kyc-service internal           |
| /internal/api/v1/accounts/**  | 8085        | account-service internal          |
| /internal/v1/risk/**          | 8094        | risk-scoring internal             |
| /api/v1/notifications/**      | 8089        | notification-service              |
| /api/v1/audit/**              | 8097        | audit-query-jvm                   |

---

## JWT validation flow

```
JWKS URI: http://localhost:8010/api/v1/auth/.well-known/jwks.json
Algorithm: RS256 (rejects HS256, alg=none)
Issuer check: nexus-platform
Blacklist check: Redis key jwt:blacklist:{jti} — 100ms timeout, fail-open
```

---

## Notes

- Downstream services NEVER validate JWTs themselves — they trust X-User-Id set by gateway
- X-User-Id is stripped from external requests first (RequestSanitizationFilter) then re-set after validation
- Fail-open on Redis outage: if blacklist check times out, request is allowed through (availability > absolute security)
- Circuit breaker: opens after 90% failure rate over minimum 100 calls, waits 10s before half-open
