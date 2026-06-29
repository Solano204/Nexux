# NEXUS Platform — Complete Endpoint Execution Guide

> All public endpoints go through the API Gateway on **port 8080**.
> Internal endpoints (`/internal/**`) are Docker-network only (172.20.0.0/16).
> Base URL for all tests: `http://localhost:8080`

---

## STEP 0 — Startup Order

Start services in this exact order or `docker compose -f docker-compose-prod.yml up -d` handles it automatically.

```
1. PostgreSQL      (5434)   ← wait healthy
2. MongoDB         (27019)  ← wait healthy
3. Redis           (6381)   ← wait healthy
4. Kafka+Zookeeper (19093)  ← wait healthy
5. Elasticsearch   (9202)   ← wait healthy
6. Zipkin          (9413)
7. Prometheus      (9093)
8. Grafana         (3002)
9. Kafdrop         (9003)
─────────────────────────────
10. nexus-config-service    (8888) ← wait healthy
11. nexus-discovery-service (8761) ← wait healthy
─────────────────────────────
12. nexus-identity-service  (8083)
13. nexus-account-service   (8085)
14. nexus-transaction-service (8086)
15. nexus-fraud-service     (8087)
16. nexus-ledger-service    (8088)
17. nexus-notification-service (8089)
18. nexus-ai-assistant-service (8090)
19. nexus-ai-kyc-service    (8091)
20. nexus-analytics-service (8092)
21. nexus-risk-scoring-service (8094)
22. nexus-saga-orchestrator (8095)
23. nexus-audit-query-jvm   (8097)
24. audit-write-native      (8096)  ← Quarkus, Kafka consumer only
─────────────────────────────
25. nexus-api-gateway       (8080)  ← last
```

**Health check all services before testing:**
```bash
# Infrastructure UIs
http://localhost:8761        # Eureka dashboard (all services must appear registered)
http://localhost:9003        # Kafdrop (Kafka topics)
http://localhost:3002        # Grafana (admin / NexusGrafana2026Admin)
http://localhost:9413        # Zipkin traces
```

---

## STEP 1 — Verify Gateway is Up

```
GET http://localhost:8080/actuator/health
```
Expected: `{"status": "UP"}`

```
GET http://localhost:8080/actuator/gateway/routes
```
Expected: JSON array of all configured routes.

---

## STEP 2 — Identity Service (Port 8083 | via Gateway :8080)

**Run in this exact order — each step depends on the previous.**

### 2.1 Register a new user
```
POST http://localhost:8080/api/v1/auth/register
Content-Type: application/json

{
  "email": "test@nexusbank.com",
  "password": "Test1234!",
  "firstName": "Carlos",
  "lastName": "Lopez",
  "phoneNumber": "+52551234567"
}
```
Response: `201 Created` → save the `userId` from response.

---

### 2.2 Login
```
POST http://localhost:8080/api/v1/auth/login
Content-Type: application/json

{
  "email": "test@nexusbank.com",
  "password": "Test1234!"
}
```
Response: `200 OK` → save `accessToken`. Refresh token is in HttpOnly cookie `refreshToken`.

> **From this point forward, all requests need:**
> `Authorization: Bearer <accessToken>`

---

### 2.3 Get JWKS (public key — no auth needed)
```
GET http://localhost:8080/api/v1/auth/.well-known/jwks.json
```
Response: RSA public key set used by the gateway to verify JWTs.

---

### 2.4 Get my profile
```
GET http://localhost:8080/api/v1/users/me
Authorization: Bearer <accessToken>
```
Response: user profile, KYC status, account status.

---

### 2.5 Change password
```
POST http://localhost:8080/api/v1/users/me/change-password
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "currentPassword": "Test1234!",
  "newPassword": "NewPass2026!"
}
```
Response: `200 OK`

---

### 2.6 Initiate KYC (document upload)
```
POST http://localhost:8080/api/v1/users/me/kyc/initiate
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data

document: <image file>
documentType: PASSPORT   (or: INE, CEDULA, DRIVERS_LICENSE)
```
Response: `202 Accepted` → save `verificationId`.
> KYC processing is async — AI KYC service analyzes the document.

---

### 2.7 Check KYC status
```
GET http://localhost:8080/api/v1/users/me/kyc/status
Authorization: Bearer <accessToken>
```
Response: `{ "status": "PENDING" | "APPROVED" | "REJECTED" | "REVIEW_REQUIRED" }`

---

### 2.8 List active sessions
```
GET http://localhost:8080/api/v1/users/me/sessions
Authorization: Bearer <accessToken>
```
Response: list of active sessions. Save a `sessionId` to test termination.

---

### 2.9 Terminate a session
```
DELETE http://localhost:8080/api/v1/users/me/sessions/{sessionId}
Authorization: Bearer <accessToken>
```
Response: `204 No Content`

---

### 2.10 Request password reset (no auth — public)
```
POST http://localhost:8080/api/v1/auth/password-reset/request
Content-Type: application/json

{ "email": "test@nexusbank.com" }
```
Response: always `200 OK` (prevents user enumeration).

---

### 2.11 Logout
```
POST http://localhost:8080/api/v1/auth/logout
Authorization: Bearer <accessToken>
```
Response: `200 OK` + clears `refreshToken` cookie.

---

### 2.12 Internal — Identity check (Docker network only)
```
GET http://localhost:8083/internal/v1/users/{userId}/identity
X-Calling-Service: nexus-fraud-service
```

### 2.13 Internal — KYC status check (Docker network only)
```
GET http://localhost:8083/internal/v1/users/{userId}/kyc/status
```

---

## STEP 3 — Account Service (Port 8085 | via Gateway :8080)

> Prerequisite: User must exist (Step 2.1). Default accounts are auto-created after KYC approval via `POST /internal/api/v1/accounts/create-defaults`.

### 3.1 List my accounts
```
GET http://localhost:8080/api/v1/accounts
Authorization: Bearer <accessToken>
```
Response: list of accounts (CHECKING + SAVINGS by default). Save `accountId`.

---

### 3.2 Get account detail
```
GET http://localhost:8080/api/v1/accounts/{accountId}
Authorization: Bearer <accessToken>
```
Response: full account detail including balance, status, currency.

---

### 3.3 Get balance (Redis cache — fast)
```
GET http://localhost:8080/api/v1/accounts/{accountId}/balance
Authorization: Bearer <accessToken>
```
Response: `200 OK` with balance, or `503` if cache warming (retry after 1s).

---

### 3.4 Get account events (paginated)
```
GET http://localhost:8080/api/v1/accounts/{accountId}/events?page=0&size=20
Authorization: Bearer <accessToken>
```

---

### 3.5 Get account analytics (MongoDB)
```
GET http://localhost:8080/api/v1/accounts/{accountId}/analytics
Authorization: Bearer <accessToken>
```

---

### 3.6 AI Financial Advisor — Chat (SSE streaming)
```
POST http://localhost:8080/api/v1/accounts/{accountId}/advisor/chat
Authorization: Bearer <accessToken>
Content-Type: application/json
Accept: text/event-stream

{
  "message": "How can I improve my savings?",
  "sessionId": "session-001"
}
```
Response: `text/event-stream` — tokens stream in real time.

---

### 3.7 AI Financial Advisor — Proactive Insights
```
GET http://localhost:8080/api/v1/accounts/{accountId}/advisor/insights
Authorization: Bearer <accessToken>
```
Response: structured AI-generated savings opportunities and action items.

---

### Internal Account Endpoints (Docker network only, port 8085)

```
# Create default accounts for a new user (called by identity-service after KYC)
POST http://localhost:8085/internal/api/v1/accounts/create-defaults
{ "userId": "<uuid>", "currency": "MXN" }

# Reserve balance for a transfer (called by saga-orchestrator)
POST http://localhost:8085/internal/api/v1/accounts/{accountId}/reserve
{ "transactionId": "<uuid>", "amount": 500.00 }

# Release reserved balance (saga compensation)
POST http://localhost:8085/internal/api/v1/accounts/{accountId}/release
{ "transactionId": "<uuid>", "amount": 500.00 }

# Finalize transfer (debit + credit atomically)
POST http://localhost:8085/internal/api/v1/accounts/finalize-transfer
{ "sourceAccountId": "<uuid>", "targetAccountId": "<uuid>", "transactionId": "<uuid>", "amount": 500.00 }

# Freeze account (called by fraud-service)
POST http://localhost:8085/internal/api/v1/accounts/{accountId}/freeze
{ "reason": "FRAUD_DETECTED" }

# Unfreeze account
POST http://localhost:8085/internal/api/v1/accounts/{accountId}/unfreeze

# Balance check (non-cached, direct PostgreSQL read)
GET http://localhost:8085/internal/api/v1/accounts/{accountId}/balance-check

# Accounts by userId
GET http://localhost:8085/internal/api/v1/accounts/by-user/{userId}
```

---

## STEP 4 — Transaction Service (Port 8086 | via Gateway :8080)

> Prerequisite: At least 2 accounts must exist (source + target).

### 4.1 Initiate a transfer
```
POST http://localhost:8080/api/v1/transactions/transfer
Authorization: Bearer <accessToken>
Content-Type: application/json
X-Idempotency-Key: txn-test-001

{
  "sourceAccountId": "<uuid>",
  "targetAccountId": "<uuid>",
  "amount": 100.00,
  "currency": "MXN",
  "description": "Test transfer"
}
```
Response: `202 Accepted` → save `transactionId`. Saga starts asynchronously.
> Transaction goes through: INITIATED → FRAUD_CHECKING → BALANCE_RESERVING → LEDGER_POSTING → COMPLETED

---

### 4.2 Initiate a payment
```
POST http://localhost:8080/api/v1/transactions/payment
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "sourceAccountId": "<uuid>",
  "targetAccountId": "<uuid>",
  "amount": 50.00,
  "currency": "MXN",
  "description": "Test payment",
  "merchantId": "merchant-001"
}
```

---

### 4.3 Get transaction history (paginated)
```
GET http://localhost:8080/api/v1/transactions?page=0&size=20
Authorization: Bearer <accessToken>
```

---

### 4.4 Get single transaction
```
GET http://localhost:8080/api/v1/transactions/{transactionId}
Authorization: Bearer <accessToken>
```
Response: full detail including current saga step and status.

---

### 4.5 Search transactions (Elasticsearch)
```
GET http://localhost:8080/api/v1/transactions/search?query=transfer
Authorization: Bearer <accessToken>
```

---

### Internal Transaction Endpoints (Docker network only, port 8086)

```
# Get transaction status (used by saga for recovery)
GET http://localhost:8086/internal/v1/transactions/{transactionId}/status

# Active transactions for an account
GET http://localhost:8086/internal/v1/accounts/{accountId}/transactions/active

# Force-compensate a stuck transaction (admin)
POST http://localhost:8086/internal/v1/transactions/{transactionId}/force-compensate

# Real-time metrics
GET http://localhost:8086/internal/v1/transactions/metrics
```

---

## STEP 5 — Fraud Service (Port 8087 | Docker network only)

> No public API — all endpoints are internal. Access directly on port 8087.

```
# Order to execute:

# 1. Direct fraud analysis (bypasses Kafka — good for testing)
POST http://localhost:8087/internal/v1/fraud/analyze
{
  "transactionId": "<uuid>",
  "userId": "<uuid>",
  "sourceAccountId": "<uuid>",
  "targetAccountId": "<uuid>",
  "amount": 100.00,
  "currency": "MXN",
  "transactionType": "TRANSFER"
}

# 2. Get fraud decision by transactionId
GET http://localhost:8087/internal/v1/fraud/decisions/{transactionId}

# 3. Get all decisions for a user (paginated)
GET http://localhost:8087/internal/v1/fraud/decisions/user/{userId}?page=0&size=20

# 4. Get pending review queue
GET http://localhost:8087/internal/v1/fraud/decisions/pending-reviews

# 5. Blacklist a merchant
POST http://localhost:8087/internal/v1/fraud/merchants/blacklist/{merchantId}

# 6. Remove merchant from blacklist
DELETE http://localhost:8087/internal/v1/fraud/merchants/blacklist/{merchantId}

# 7. Record manual review outcome
POST http://localhost:8087/internal/v1/fraud/review/{decisionId}/outcome
{
  "reviewerId": "<uuid>",
  "outcome": "CONFIRMED_FRAUD",   (or: CLEARED, ESCALATED)
  "notes": "Clear fraud pattern"
}

# 8. Record SAR filing
POST http://localhost:8087/internal/v1/fraud/review/{decisionId}/sar
{ "sarReference": "SAR-2026-001" }

# 9. Real-time fraud metrics
GET http://localhost:8087/internal/v1/fraud/metrics

# 10. Policy search
GET http://localhost:8087/internal/v1/fraud/policies/search?query=large+transaction
```

---

## STEP 6 — Ledger Service (Port 8088 | via Gateway :8080)

> Ledger entries are written automatically when transactions complete via Kafka. Test queries after running a transfer (Step 4.1).

### 6.1 Get ledger balance
```
GET http://localhost:8080/api/v1/ledger/accounts/{accountId}/balance
Authorization: Bearer <accessToken>
```

### 6.2 Get ledger entries (paginated)
```
GET http://localhost:8080/api/v1/ledger/accounts/{accountId}/entries?page=0&size=20
Authorization: Bearer <accessToken>
```

### 6.3 Get monthly summary
```
GET http://localhost:8080/api/v1/ledger/accounts/{accountId}/summary/monthly?year=2026&month=6
Authorization: Bearer <accessToken>
```

### 6.4 Get posting detail for a transaction
```
GET http://localhost:8080/api/v1/ledger/transactions/{transactionId}/posting
Authorization: Bearer <accessToken>
```

### 6.5 AI Ledger Explainer (SSE streaming)
```
POST http://localhost:8080/api/v1/ledger/accounts/{accountId}/explain
Authorization: Bearer <accessToken>
Content-Type: application/json
Accept: text/event-stream

{
  "message": "Why was my balance reduced by 100 MXN?",
  "sessionId": "explain-001"
}
```
Response: `text/event-stream` — streaming AI explanation of transactions.

---

### Internal Ledger Endpoints (Docker network only, port 8088)

```
# Authoritative ledger balance
GET http://localhost:8088/internal/v1/ledger/accounts/{accountId}/balance

# Manual adjustment (admin)
POST http://localhost:8088/internal/v1/ledger/postings/manual
{
  "sourceAccountId": "<uuid>",
  "targetAccountId": "<uuid>",
  "amount": 100.00,
  "currency": "MXN",
  "reason": "CORRECTION",
  "approvalReference": "APPR-2026-001"
}

# Reverse a posting
POST http://localhost:8088/internal/v1/ledger/postings/{postingId}/reverse
{ "reason": "Duplicate posting error" }

# Reconciliation status
GET http://localhost:8088/internal/v1/ledger/reconciliation/status

# Force balance reconstruction
POST http://localhost:8088/internal/v1/ledger/accounts/{accountId}/reconstruct

# Trigger integrity verification
GET http://localhost:8088/internal/v1/ledger/integrity/verify
```

---

## STEP 7 — Notification Service (Port 8089 | via Gateway :8080)

> Notifications are sent automatically via Kafka when events happen. Test queries after user registration and transactions.

### 7.1 Get my notifications (paginated)
```
GET http://localhost:8080/api/v1/notifications?page=0&size=20
Authorization: Bearer <accessToken>
```

### 7.2 Get unread count (Redis)
```
GET http://localhost:8080/api/v1/notifications/unread-count
Authorization: Bearer <accessToken>
```
Response: `{ "unreadCount": 3 }`

### 7.3 Mark a notification as read
```
PATCH http://localhost:8080/api/v1/notifications/{notificationId}/read
Authorization: Bearer <accessToken>
```

### 7.4 Mark all as read
```
PATCH http://localhost:8080/api/v1/notifications/read-all
Authorization: Bearer <accessToken>
```

### 7.5 Get notification preferences
```
GET http://localhost:8080/api/v1/notifications/preferences
Authorization: Bearer <accessToken>
```

### 7.6 Update notification preferences
```
PUT http://localhost:8080/api/v1/notifications/preferences
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "language": "es",
  "timezone": "America/Mexico_City",
  "globalOptOut": false,
  "eventPreferences": {
    "TRANSACTION_COMPLETED": { "enabled": true },
    "FRAUD_ALERT": { "enabled": true }
  }
}
```
> NOTE: `FRAUD_ALERT` cannot be disabled (regulatory requirement — returns 400 if attempted).

### 7.7 Register push notification device
```
POST http://localhost:8080/api/v1/notifications/preferences/device
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "deviceToken": "fcm-token-abc123",
  "platform": "FCM"
}
```

### 7.8 Unregister push device
```
DELETE http://localhost:8080/api/v1/notifications/preferences/device/{deviceToken}
Authorization: Bearer <accessToken>
```

---

## STEP 8 — AI Assistant Service (Port 8090 | via Gateway :8080/ai/**)

> Gateway route strips `/ai` prefix: `POST /ai/chat` → service receives `POST /api/v1/ai/chat`

### 8.1 Chat with AI Assistant (SSE streaming)
```
POST http://localhost:8080/api/v1/ai/chat
Authorization: Bearer <accessToken>
Content-Type: application/json
Accept: text/event-stream

{
  "message": "Show me my spending pattern for this month",
  "sessionId": "chat-session-001"
}
```
Response: `text/event-stream` — streaming AI response with tool calls visible.

### 8.2 Document analysis — Chat (multipart + SSE)
```
POST http://localhost:8080/api/v1/ai/chat/analyze-document
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data
Accept: text/event-stream

file: <image or PDF>
message: "What is the total amount on this receipt?"
sessionId: doc-001
```

### 8.3 Document analysis — Dedicated endpoint
```
POST http://localhost:8080/api/v1/ai/documents/analyze
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data
Accept: text/event-stream

file: <image or PDF>
message: "Extract all line items from this invoice"
sessionId: doc-002
```

---

## STEP 9 — AI KYC Service (Port 8091 | via Gateway :8080)

### 9.1 Submit identity verification
```
POST http://localhost:8080/api/v1/kyc/verify
X-User-Id: <userId>  (set by gateway after JWT validation)
Content-Type: multipart/form-data

document: <id document image>
fullName: Carlos Lopez
dateOfBirth: 1990-01-15
documentNumber: ABC123456
documentType: PASSPORT   (or: INE, CEDULA, DRIVERS_LICENSE)
nationality: MX
language: es
```
Response: `{ "verificationId": "...", "status": "PROCESSING" }`

### 9.2 Check verification status
```
GET http://localhost:8080/api/v1/kyc/status/{verificationId}
X-User-Id: <userId>
```
Response: `{ "status": "APPROVED|REJECTED|PROCESSING|REVIEW_REQUIRED", "userFacingMessage": "...", "canRetry": true }`

---

### Internal KYC Endpoints (Docker network only, port 8091)

```
# Full verification record
GET http://localhost:8091/internal/v1/kyc/verifications/{verificationId}

# All verifications for a user
GET http://localhost:8091/internal/v1/kyc/verifications/user/{userId}

# PostgreSQL audit trail for a verification
GET http://localhost:8091/internal/v1/kyc/verifications/{verificationId}/audit

# Check retry eligibility (3 attempts per 30 days)
GET http://localhost:8091/internal/v1/kyc/retry-eligibility/{userId}

# Submit manual review outcome (compliance officer)
POST http://localhost:8091/internal/v1/kyc/review/{verificationId}/outcome
{
  "reviewOutcome": "APPROVED",   (or: REJECTED)
  "reviewNotes": "Document verified manually",
  "reviewedBy": "<officerId>"
}

# Record SAR filing
POST http://localhost:8091/internal/v1/kyc/verifications/{verificationId}/sar
{ "sarReferenceNumber": "SAR-2026-001", "filedBy": "<officerId>" }

# Daily metrics
GET http://localhost:8091/internal/v1/kyc/metrics/daily

# Trigger re-verification
POST http://localhost:8091/internal/v1/kyc/re-verify/{userId}
{ "reason": "COMPLIANCE_REQUESTED" }
```

---

## STEP 10 — Analytics Service (Port 8092 | via Gateway :8080)

> Analytics are computed from Kafka Streams. Data appears after transactions have been processed.

### 10.1 Monthly analytics for an account
```
GET http://localhost:8080/api/v1/analytics/accounts/{accountId}/monthly/2026-06
Authorization: Bearer <accessToken>
```

### 10.2 Spending trends
```
GET http://localhost:8080/api/v1/analytics/accounts/{accountId}/trends
Authorization: Bearer <accessToken>
```

### 10.3 Top merchants
```
GET http://localhost:8080/api/v1/analytics/accounts/{accountId}/merchants?limit=10
Authorization: Bearer <accessToken>
```

### 10.4 Platform real-time metrics (Redis)
```
GET http://localhost:8080/api/v1/analytics/platform/realtime
Authorization: Bearer <accessToken>
```

### 10.5 AI Financial Insights
```
GET http://localhost:8080/api/v1/analytics/accounts/{accountId}/insights/2026-06?language=es
Authorization: Bearer <accessToken>
```
Response: AI-generated insights cached 1 hour in Redis.

---

### Internal Analytics Endpoints (Docker network only, port 8092)

```
# Kafka Streams category spending (used by fraud-service)
GET http://localhost:8092/internal/v1/streams/category-spending?userId=<id>&category=FOOD&date=2026-06-16

# Kafka Streams lag (monitoring)
GET http://localhost:8092/internal/v1/streams/health/lag
```

---

## STEP 11 — Risk Scoring Service (Port 8094 | Docker network only)

> No public API — all internal. Risk profiles computed nightly or on-demand.

```
# 1. Get current risk profile
GET http://localhost:8094/internal/v1/risk/profiles/{userId}

# 2. Quick tier lookup (Redis cached)
GET http://localhost:8094/internal/v1/risk/profiles/{userId}/tier
Response: { "userId": "...", "riskTier": "LOW" }   (VERY_LOW|LOW|MEDIUM|HIGH|VERY_HIGH)

# 3. Profile version history
GET http://localhost:8094/internal/v1/risk/profiles/{userId}/history

# 4. Trigger manual recomputation (AI agent)
POST http://localhost:8094/internal/v1/risk/profiles/{userId}/compute
Response: { "status": "COMPUTED", "overallRiskScore": 23.5, "riskTier": "LOW" }

# 5. Trigger manual nightly batch
POST http://localhost:8094/internal/v1/risk/batch/trigger

# 6. Batch job status
GET http://localhost:8094/internal/v1/risk/batch/status

# 7. Platform risk distribution
GET http://localhost:8094/internal/v1/risk/stats
Response: { "veryLow": 150, "low": 300, "medium": 80, "high": 20, "veryHigh": 5, ... }
```

---

## STEP 12 — Saga Orchestrator (Port 8095 | Docker network only)

> Sagas run automatically via Kafka. These endpoints are for monitoring and debugging.

```
# 1. Get transfer saga state
GET http://localhost:8095/internal/v1/sagas/transfer/{transactionId}

# 2. Get onboarding saga state (KYC + account creation)
GET http://localhost:8095/internal/v1/sagas/onboarding/{userId}

# 3. Step-by-step history of a transfer saga
GET http://localhost:8095/internal/v1/sagas/transfer/{transactionId}/history

# 4. Active saga counts
GET http://localhost:8095/internal/v1/sagas/stats
Response: { "activeTransferSagas": 5, "activeOnboardingSagas": 2, "status": "OPERATIONAL" }

# 5. Stuck sagas (expired + not in terminal state)
GET http://localhost:8095/internal/v1/sagas/stuck
```

---

## STEP 13 — Audit Query JVM (Port 8097 | via Gateway :8080)

> Audit events are written by audit-write-native via Kafka. Data appears after events flow through the system.

### 13.1 Get user audit events
```
GET http://localhost:8080/api/v1/audit/users/{userId}/events?page=0&size=50
Authorization: Bearer <accessToken>
```

### 13.2 Cross-service transaction trace (Elasticsearch)
```
GET http://localhost:8080/api/v1/audit/transactions/{transactionId}/trace
Authorization: Bearer <accessToken>
```
Response: all audit events across all services for this transaction, in order.

### 13.3 Platform statistics
```
GET http://localhost:8080/api/v1/audit/platform/statistics
Authorization: Bearer <accessToken>
```
Response: total audit events count.

### 13.4 Compliance — Natural language query (COMPLIANCE_OFFICER role required)
```
POST http://localhost:8080/api/v1/audit/compliance/query
Authorization: Bearer <complianceOfficerToken>
Content-Type: application/json

{
  "naturalLanguageQuery": "Show all large transactions over 10000 MXN in the last 30 days",
  "targetUserId": "<userId>",
  "startDate": "2026-05-17",
  "endDate": "2026-06-16",
  "queryType": "SUSPICIOUS_ACTIVITY"
}
```

### 13.5 User timeline (COMPLIANCE_OFFICER / ADMIN role)
```
GET http://localhost:8080/api/v1/audit/users/{userId}/timeline?page=0&size=50
Authorization: Bearer <complianceOfficerToken>
```

### 13.6 Active compliance alerts
```
GET http://localhost:8080/api/v1/audit/compliance/alerts?severity=WARNING,CRITICAL
Authorization: Bearer <complianceOfficerToken>
```

### 13.7 Compliance reports
```
GET http://localhost:8080/api/v1/audit/compliance/reports?page=0&size=20
Authorization: Bearer <complianceOfficerToken>
```

---

## STEP 14 — Audit Write Native (Port 8096 | Quarkus)

> **No REST API for business operations — this is a pure Kafka consumer.**
> It writes to Elasticsearch and MongoDB automatically.

```
# Health
GET http://localhost:8096/q/health

# Metrics (Prometheus)
GET http://localhost:8096/q/metrics
```

**Kafka topics consumed automatically:**
| Topic | Trigger |
|---|---|
| `transactions.completed` | After successful transfer |
| `transactions.failed` | After failed transfer |
| `transactions.initiated` | When transfer starts |
| `ledger.posted` | After ledger entry created |
| `ledger.reversed` | After ledger reversal |
| `fraud.result` | After fraud analysis |
| `fraud.flagged` | After fraud detection |
| `account.frozen` | After account freeze |
| `accounts.created` | After account creation |
| `users.registered` | After registration |
| `identity.verified` | After KYC approval |
| `identity.rejected` | After KYC rejection |
| `saga.completed` | After saga finishes |
| `ai.query.logged` | After AI assistant query |
| `analytics.anomalies.detected` | After analytics anomaly |

---

## STEP 15 — Config & Discovery (Infrastructure only)

### Config Service (Port 8888)
```
# Health
GET http://localhost:8888/actuator/health

# Get config for a service (not for end users)
GET http://localhost:8888/nexus-fraud-service/prod
Authorization: Basic nexus-config:NexusConfig2026Pass
```

### Discovery Service (Port 8761)
```
# Eureka dashboard
GET http://localhost:8761/

# Health
GET http://localhost:8761/actuator/health

# Registered instances (all 16 services should appear)
GET http://localhost:8761/eureka/apps
Accept: application/json
```

---

## Full Happy Path Test Sequence (new user end-to-end)

Run in this order to test the complete user journey:

```
1.  POST /api/v1/auth/register           → get userId
2.  POST /api/v1/auth/login              → get accessToken
3.  GET  /api/v1/auth/.well-known/jwks.json
4.  POST /api/v1/users/me/kyc/initiate   → get verificationId
5.  GET  /api/v1/users/me/kyc/status     → wait for APPROVED
    (internal: identity-service calls kyc-service callback)
    (saga: OnboardingSaga creates accounts automatically)
6.  GET  /api/v1/accounts                → get accountId (CHECKING + SAVINGS)
7.  GET  /api/v1/accounts/{id}/balance
8.  POST /api/v1/transactions/transfer   → get transactionId
    (saga: INITIATED → FRAUD_CHECKING → BALANCE_RESERVING → LEDGER_POSTING → COMPLETED)
9.  GET  /api/v1/transactions/{id}       → verify COMPLETED
10. GET  /api/v1/ledger/accounts/{id}/entries → see debit/credit entries
11. GET  /api/v1/notifications           → see transaction notification
12. GET  /api/v1/audit/users/{userId}/events → full audit trail
13. GET  /api/v1/audit/transactions/{txnId}/trace → cross-service trace
14. POST /api/v1/ai/chat                 → ask about the transaction (SSE)
15. POST /api/v1/auth/logout
```

---

## Monitoring Endpoints

| URL | What to check |
|---|---|
| http://localhost:8761 | All 16 services registered in Eureka |
| http://localhost:9003 | Kafka topics have messages |
| http://localhost:3002 | Grafana dashboards — metrics flowing |
| http://localhost:9413 | Zipkin — distributed traces |
| http://localhost:8080/actuator/health | Gateway circuit breakers |
| http://localhost:8080/actuator/circuitbreakers | CB state per service |

---

## Common Headers Reference

| Header | Required for | Value |
|---|---|---|
| `Authorization` | All authenticated endpoints | `Bearer <accessToken>` |
| `Content-Type` | POST/PUT with JSON | `application/json` |
| `Content-Type` | File uploads | `multipart/form-data` |
| `Accept` | SSE streaming endpoints | `text/event-stream` |
| `X-Idempotency-Key` | Transactions | unique UUID per request |
| `X-Calling-Service` | Internal endpoints | service name |

---

## Base URLs Summary

| Mode | Base URL |
|---|---|
| All public traffic | `http://localhost:8080` |
| Identity (direct) | `http://localhost:8083` |
| Account (direct) | `http://localhost:8085` |
| Transaction (direct) | `http://localhost:8086` |
| Fraud (internal only) | `http://localhost:8087` |
| Ledger (direct) | `http://localhost:8088` |
| Notification (direct) | `http://localhost:8089` |
| AI Assistant (direct) | `http://localhost:8090` |
| AI KYC (direct) | `http://localhost:8091` |
| Analytics (direct) | `http://localhost:8092` |
| Risk Scoring (internal) | `http://localhost:8094` |
| Saga Orchestrator (internal) | `http://localhost:8095` |
| Audit Write Native | `http://localhost:8096` |
| Audit Query JVM (direct) | `http://localhost:8097` |
| Config Server | `http://localhost:8888` |
| Eureka | `http://localhost:8761` |
