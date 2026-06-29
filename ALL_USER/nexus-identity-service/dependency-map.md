# nexus-identity-service — Complete Dependency Map

---

## Infrastructure identity service REQUIRES

| Component        | Port  | Why                                                                             |
|------------------|-------|---------------------------------------------------------------------------------|
| PostgreSQL       | 5433  | users, sessions, kyc_verifications, audit_log, outbox, password_history tables  |
| Redis            | 6380  | JWT blacklist + session cache + @Cacheable("user-profile")                      |
| Kafka            | 19092 | Spring Cloud Bus (config refresh) + saga.commands consumer                      |
| Config service   | 8888  | loads all config on startup                                                     |
| Discovery service| 8761  | Eureka registration                                                              |

---

## Services that call identity (identity is the dependency)

| Caller            | What it calls                                                                 |
|-------------------|-------------------------------------------------------------------------------|
| API Gateway       | GET /.well-known/jwks.json to validate JWTs on every authenticated request    |
| Account service   | GET /internal/v1/users/{id}/identity to verify user before creating account   |
| Fraud service     | GET /internal/v1/users/{id}/identity to enrich fraud checks                   |
| KYC service       | POST /internal/v1/users/{id}/kyc/result to deliver AI decision back           |
| Saga orchestrator | sends InitiateKycVerificationCommand on saga.commands topic                   |

---

## Services identity calls outbound

| Target               | How                              | Why                                      |
|----------------------|----------------------------------|------------------------------------------|
| Notification service | Kafka → saga.commands            | SendWelcomeNotificationCommand on complete|
| KYC service          | Kafka → identity.kyc topic       | triggers KYC verification                |
| Saga orchestrator    | Kafka → saga.replies             | sends KycVerificationReply back          |

---

## Direct dependencies (identity owns the connection)

| Component         | Status      |
|-------------------|-------------|
| PostgreSQL        | Direct      |
| Redis             | Direct      |
| Kafka             | Direct      |
| Config service    | Direct      |
| Discovery service | Direct      |
| MongoDB           | NOT direct  |
| Elasticsearch     | NOT direct  |
| S3                | NOT direct  |
| SQS               | NOT direct  |
| OpenAI            | NOT direct  |

---

## Indirect dependencies (hit during full onboarding flow triggered by identity)

| Component     | Indirect role in the identity flow                                                                                                         |
|---------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| MongoDB       | audit-write-native writes every identity event (register, login, KYC, logout) to nexus_audit collection. notification-service stores sent emails/SMS there too. |
| Elasticsearch | audit-write-native dual-writes to nexus-audit-* index simultaneously. The audit trail queryable with .keyword — that's this.              |
| S3            | ai-kyc-service uploads the ID document and selfie to S3 before running Rekognition. Identity triggers KYC → KYC needs S3.                 |
| SQS           | ai-kyc-service uses SQS to queue the async AI analysis job after receiving the identity.kyc Kafka event. Without SQS the KYC pipeline stalls. |
| OpenAI        | ai-kyc-service calls OpenAI to analyze document text (OCR extraction + fraud scoring). Also used by fraud-service when a transaction is flagged. |

---

## Full onboarding flow dependency chain

```
identity → Postgres + Redis + Kafka
         → KYC service    → S3 + SQS + OpenAI + Postgres(kyc db)
         → audit-write    → MongoDB + Elasticsearch
         → notification   → MongoDB
         → saga           → Postgres(saga db)
         → account        → MongoDB
```

---

## Endpoints tested

| Endpoint                                              | Method | Result |
|-------------------------------------------------------|--------|--------|
| /api/v1/auth/register                                 | POST   | OK     |
| /api/v1/auth/login                                    | POST   | OK     |
| /api/v1/auth/logout                                   | POST   | OK (fixed — was not blacklisting token) |
| /api/v1/auth/.well-known/jwks.json                    | GET    | OK     |
| /api/v1/auth/password-reset/request                   | POST   | OK     |
| /api/v1/users/me                                      | GET    | OK (fixed — Redis port 6379→6380)       |
| /api/v1/kyc/initiate                                  | POST   | OK     |
| /internal/v1/users/{userId}/identity                  | GET    | OK (fixed — missing gateway route)      |
| /internal/v1/users/{userId}/kyc/status                | GET    | OK (fixed — missing gateway route)      |
| /internal/v1/health/detailed                          | GET    | OK (fixed — missing gateway route)      |

---

## Bugs found and fixed during testing

| Bug | Fix |
|-----|-----|
| Redis port 6379 in 5 service-specific dev configs | Changed all to 6380 in config server files |
| Logout not blacklisting token — X-User-Id null on public route | Decode JWT directly in controller to extract sub + exp |
| Token TTL in blacklist hardcoded to 900s | Use decoded.getExpiresAtAsInstant() |
| /internal/v1/users/** and /internal/v1/health/** missing from gateway dev routes | Added nexus-identity-service-internal route to application-dev.yml |
| /internal/v1/users/** and /internal/v1/health/** missing from gateway prod routes | Added nexus-identity-service-internal route to application.yml |
| Saga stuck — NotificationSentReply routed to transferProcessor for all notifications | Route by originalCommand field: SendWelcomeNotificationCommand → onboardingProcessor |
