# 3 — nexus-identity-service
**Port:** 8083 | **Gateway base:** http://localhost:8080

## External Dependencies
- PostgreSQL (nexus_identity DB on port 5433)
- Redis (port 6380) — session cache + JWT blacklist
- Kafka (port 19092) — saga commands consumer
- AWS S3 + SQS — ONLY for KYC initiate endpoint
- JWT keystore: nexus-identity-service/keys/nexus-identity.jks

## Variables to SAVE during testing
- `{userId}` — from register response
- `{accessToken}` — from login response
- `{sessionId}` — from GET /users/me/sessions response
- `{verificationId}` — from KYC initiate response

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8083/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

---

### PUBLIC ENDPOINTS (no token needed)

### 2. Register user — SAVE userId
```
POST http://localhost:8080/api/v1/auth/register
Content-Type: application/json

{
  "firstName": "Carlos",
  "lastName": "Lopez",
  "email": "carlos@test.com",
  "password": "Test1234!",
  "phone": "+521234567890"
}
```
Expected: 201 — body contains `userId`

> **Kafka topics:** `users.registered` (published via Debezium outbox)
> **Cascade topics:** `users.registered` → saga-orchestrator → `saga.commands` → account-service → `accounts.created` → `saga.onboarding.complete`
> **DB affected:**
> - PostgreSQL `nexus_identity.users` — INSERT (userId, email, passwordHash, firstName, lastName, phone, status=PENDING)
> - PostgreSQL `nexus_identity.outbox` — INSERT (aggregate_type=users.registered, Debezium reads this)
> **Reacted by:** nexus-saga-orchestrator (OnboardingSaga), nexus-notification-service, audit-write-native

### 3. Login — SAVE accessToken
```
POST http://localhost:8080/api/v1/auth/login
Content-Type: application/json

{
  "email": "carlos@test.com",
  "password": "Test1234!"
}
```
Expected: 200 — body contains `accessToken`. `refreshToken` is HttpOnly cookie.

> **Kafka topics:** `identity.events` (published via Debezium outbox — LoginSuccessful, audit only)
> **DB affected:**
> - PostgreSQL `nexus_identity.users` — SELECT (lookup by email, verify bcrypt hash)
> - PostgreSQL `nexus_identity.outbox` — INSERT (aggregate_type=identity.events)
> - Redis `blacklist:{jti}` — GET (check token not already revoked)
> - Redis `session:{sessionId}` — SET with TTL (store new session)
> **Reacted by:** audit-write-native — writes login event to Elasticsearch

### 4. JWKS endpoint
```
GET http://localhost:8080/api/v1/auth/.well-known/jwks.json
```
Expected: 200 — JSON with `keys` array containing RSA public key

> **Kafka topics:** none
> **DB affected:** none — RSA public key is loaded in-memory from keystore at startup

### 5. Password reset request
```
POST http://localhost:8080/api/v1/auth/password-reset/request
Content-Type: application/json

{
  "email": "carlos@test.com"
}
```
Expected: 200 — always returns success regardless of email existence

> **Kafka topics:** `password.reset.requested` (published via Debezium outbox)
> **DB affected:**
> - PostgreSQL `nexus_identity.users` — SELECT (lookup by email to generate reset token)
> - PostgreSQL `nexus_identity.outbox` — INSERT (aggregate_type=password.reset.requested, contains reset token)
> **Reacted by:** nexus-notification-service — sends password reset email

---

### AUTHENTICATED ENDPOINTS (Bearer token required)

```
Authorization: Bearer {accessToken}
```

### 6. Get my profile
```
GET http://localhost:8080/api/v1/users/me
Authorization: Bearer {accessToken}
```
Expected: 200 — user profile JSON

> **Kafka topics:** none
> **DB affected:**
> - Redis `blacklist:{jti}` — GET (validate token not revoked)
> - PostgreSQL `nexus_identity.users` — SELECT by userId

### 7. Get my sessions — SAVE sessionId
```
GET http://localhost:8080/api/v1/users/me/sessions
Authorization: Bearer {accessToken}
```
Expected: 200 — array of session objects. Save one `sessionId`.

> **Kafka topics:** none
> **DB affected:**
> - Redis `sessions:{userId}` — HGETALL (list all active sessions for user)

### 8. Initiate KYC (needs AWS S3 + SQS)
```
POST http://localhost:8080/api/v1/users/me/kyc/initiate
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data

document: [any image file]
documentType: PASSPORT
```
Expected: 202 — body contains `verificationId`. Save it.

> **Kafka topics:** `identity.kyc.initiations` (published via Debezium outbox)
> **Async cascade:** SQS → nexus-ai-kyc-service → Rekognition → callback → `identity.verified` or `identity.rejected`
> **DB affected:**
> - PostgreSQL `nexus_identity.kyc_verifications` — INSERT (verificationId, userId, status=PENDING, documentType)
> - PostgreSQL `nexus_identity.outbox` — INSERT (aggregate_type=identity.kyc.initiations)
> - AWS S3 `nexus-kyc-documents` bucket — PUT object (document file)
> - AWS SQS `nexus-kyc-documents-pending` — SendMessage (job enqueue)

### 9. Get KYC status
```
GET http://localhost:8080/api/v1/users/me/kyc/status
Authorization: Bearer {accessToken}
```
Expected: 200 — current KYC status

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_identity.kyc_verifications` — SELECT latest WHERE userId

### 10. Change password
```
POST http://localhost:8080/api/v1/users/me/change-password
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "currentPassword": "Test1234!",
  "newPassword": "NewTest5678!"
}
```
Expected: 200

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_identity.users` — SELECT (verify current bcrypt) + UPDATE password_hash
> - Redis `sessions:{userId}` — DEL all keys (force re-login on all devices)

### 11. Terminate a session
```
DELETE http://localhost:8080/api/v1/users/me/sessions/{sessionId}
Authorization: Bearer {accessToken}
```
Expected: 204

> **Kafka topics:** none
> **DB affected:**
> - Redis `session:{sessionId}` — DEL

---

### INTERNAL ENDPOINTS (port 8083 directly)

### 12. Get identity summary
```
GET http://localhost:8083/internal/v1/users/{userId}/identity
X-Calling-Service: nexus-fraud-service
```
Expected: 200 — identity summary without PII

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_identity.users` — SELECT (non-PII fields only)

### 13. Get KYC status (internal)
```
GET http://localhost:8083/internal/v1/users/{userId}/kyc/status
```
Expected: 200 — { kycVerified, accountStatus, kycDecision, verificationId }

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_identity.kyc_verifications` — SELECT latest by userId

### 14. KYC result callback (normally called by nexus-ai-kyc-service)
```
POST http://localhost:8083/internal/v1/users/{userId}/kyc/result
Content-Type: application/json

{
  "verificationId": "{verificationId}",
  "decision": "APPROVED",
  "confidence": 0.95,
  "notes": "Manual test approval"
}
```
Expected: 200

> **Kafka topics:** `identity.verified` (if APPROVED) or `identity.rejected` (if REJECTED) — via Debezium outbox
> **DB affected:**
> - PostgreSQL `nexus_identity.kyc_verifications` — UPDATE (status, decision, confidence, decidedAt)
> - PostgreSQL `nexus_identity.users` — UPDATE (kyc_verified=true, account_status=ACTIVE if approved)
> - PostgreSQL `nexus_identity.outbox` — INSERT (aggregate_type=identity.verified or identity.rejected)
> **Reacted by:** nexus-notification-service (KYC notification), audit-write-native (Elasticsearch), nexus-saga-orchestrator (resumes onboarding if APPROVED)

### 15. Detailed health (internal)
```
GET http://localhost:8083/internal/v1/health/detailed
```
Expected: 200 — { status: "UP", service, timestamp }

> **Kafka topics:** none
> **DB affected:** none — in-memory check only

---

### LAST — invalidates token

### 16. Logout
```
POST http://localhost:8080/api/v1/auth/logout
Authorization: Bearer {accessToken}
```
Expected: 200 — { "message": "Logged out successfully" }
After this: token is blacklisted. Login again if you need to test other services.

> **Kafka topics:** `identity.events` (published via Debezium outbox — LogoutSuccessful, audit only)
> **DB affected:**
> - Redis `blacklist:{jti}` — SET with TTL (token revoked immediately)
> - Redis `session:{sessionId}` — DEL
> - PostgreSQL `nexus_identity.outbox` — INSERT (aggregate_type=identity.events)
> **Reacted by:** audit-write-native — writes logout event to Elasticsearch
