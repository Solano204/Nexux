# 9 — nexus-ai-kyc-service
**Port:** 8091 | **Gateway base:** http://localhost:8080

## External Dependencies
- MongoDB (port 27018) — KYC verification records + AI results
- PostgreSQL (nexus_kyc DB on port 5433) — KYC audit trail
- Kafka (port 19092) — publishes kyc result events
- AWS SQS — inbound job queue (triggers this service)
- AWS S3 — document download
- AWS Rekognition — face/document AI analysis

## Event Flow
This service is triggered by **AWS SQS** (not Kafka) and publishes results back via **Kafka**.

| Trigger | Source |
|---|---|
| SQS poll (document pending) | nexus-identity-service uploads to S3 + enqueues to SQS |

## Kafka Events Published
| Topic | Consumed by |
|---|---|
| `identity.kyc.result` | nexus-identity-service (updates KYC status) |
| `identity.kyc.result` | nexus-notification-service (KYC_STATUS_UPDATE) |
| `identity.kyc.result` | audit-write-native (Elasticsearch) |

## Variables to SAVE
- `{verificationId}` — from POST /api/v1/kyc/verify response

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8091/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

---

### USER-FACING ENDPOINTS

### 2. Submit KYC verification — SAVE verificationId
```
POST http://localhost:8080/api/v1/kyc/verify
Authorization: Bearer {accessToken}
Content-Type: multipart/form-data

document: [image file, e.g. id-front.jpg]
fullName: Carlos Lopez
dateOfBirth: 1990-01-15
documentNumber: ABC123456
documentType: NATIONAL_ID
nationality: MX
language: es
```
Expected: 200 — { verificationId, status: PENDING, userFacingMessage }
Valid documentType: PASSPORT, NATIONAL_ID, DRIVERS_LICENSE

> **Kafka topics:** `identity.kyc.result` (published ASYNCHRONOUSLY — after Rekognition analysis, not immediately)
> **Async cascade:** SQS poll → AWS S3 download → AWS Rekognition → decision → `identity.kyc.result` → nexus-identity-service (updates kyc status) + nexus-notification-service + audit-write-native
> **DB affected (synchronous — immediate):**
> - MongoDB `nexus_kyc.kyc_verifications` — INSERT (verificationId, userId, status=PENDING, documentType, submittedAt)
> - PostgreSQL `nexus_kyc.kyc_audit` — INSERT audit entry (EVENT=SUBMITTED)
> - AWS S3 `nexus-kyc-documents` bucket — PUT object (document file)
> - AWS SQS `nexus-kyc-documents-pending` — SendMessage (job payload with S3 key)
> **DB affected (async — after Rekognition completes):**
> - MongoDB `nexus_kyc.kyc_verifications` — UPDATE (status=APPROVED/REJECTED, confidence, rekognition_response, decidedAt)
> - PostgreSQL `nexus_kyc.kyc_audit` — INSERT audit entry (EVENT=DECIDED)

### 3. Get verification status
```
GET http://localhost:8080/api/v1/kyc/status/{verificationId}
Authorization: Bearer {accessToken}
X-User-Id: {userId}
```
Expected: 200 — { verificationId, status, submittedAt, decidedAt, userFacingMessage, canRetry }

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_kyc.kyc_verifications` — findOne WHERE _id = verificationId

---

### INTERNAL ENDPOINTS (port 8091 directly)

### 4. Daily metrics
```
GET http://localhost:8091/internal/v1/kyc/metrics/daily
```
Expected: 200 — { totalVerifications, pendingProcessing, serviceStatus }

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_kyc.kyc_verifications` — COUNT grouped by status WHERE submitted_at > today midnight

### 5. Get full verification record
```
GET http://localhost:8091/internal/v1/kyc/verifications/{verificationId}
```
Expected: 200 — full MongoDB document including AI analysis results

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_kyc.kyc_verifications` — findOne WHERE _id (includes raw Rekognition response)

### 6. Get all verifications for a user
```
GET http://localhost:8091/internal/v1/kyc/verifications/user/{userId}
```
Expected: 200 — list of all verification attempts, newest first

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_kyc.kyc_verifications` — find WHERE userId, sort by submitted_at DESC

### 7. Get verification audit trail
```
GET http://localhost:8091/internal/v1/kyc/verifications/{verificationId}/audit
```
Expected: 200 — ordered list of audit entries

> **Kafka topics:** none
> **DB affected:**
> - PostgreSQL `nexus_kyc.kyc_audit` — SELECT WHERE verification_id ORDER BY occurred_at ASC

### 8. Check retry eligibility
```
GET http://localhost:8091/internal/v1/kyc/retry-eligibility/{userId}
```
Expected: 200 — { eligible, remainingAttempts, recentRejections, windowDays: 30 }

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_kyc.kyc_verifications` — COUNT WHERE userId AND status=REJECTED AND submitted_at > now()-30days
> - PostgreSQL `nexus_kyc.kyc_audit` — SELECT recent rejection events for rate-limit check

### 9. Submit manual review outcome (compliance officer)
```
POST http://localhost:8091/internal/v1/kyc/review/{verificationId}/outcome
Content-Type: application/json

{
  "reviewOutcome": "APPROVED",
  "reviewNotes": "Manual test approval — document verified",
  "reviewedBy": "{userId}"
}
```
Expected: 200 — { verificationId, reviewOutcome, status, reviewedAt }
reviewOutcome values: APPROVED, REJECTED

> **Kafka topics:** `identity.kyc.result` (published via Debezium outbox — manual override flag set)
> **DB affected:**
> - MongoDB `nexus_kyc.kyc_verifications` — UPDATE (status=APPROVED/REJECTED, manual_review=true, reviewedBy, reviewedAt)
> - PostgreSQL `nexus_kyc.kyc_audit` — INSERT (EVENT=MANUAL_REVIEW_DECIDED, reviewedBy, outcome)
> - PostgreSQL `nexus_kyc.outbox` — INSERT (aggregate_type=identity.kyc.result, manualOverride=true)
> **Reacted by:** nexus-identity-service (overrides KYC status), nexus-notification-service (KYC_STATUS_UPDATE), audit-write-native

### 10. Record SAR filing
```
POST http://localhost:8091/internal/v1/kyc/verifications/{verificationId}/sar
Content-Type: application/json

{
  "sarReferenceNumber": "SAR-KYC-TEST-2026-001",
  "filedBy": "{userId}"
}
```
Expected: 200 — { verificationId, sarReferenceNumber, filedAt, status: "SAR_RECORDED" }

> **Kafka topics:** none — SAR is recorded locally only, no downstream event
> **DB affected:**
> - MongoDB `nexus_kyc.kyc_verifications` — UPDATE (sar_reference=sarReferenceNumber, sar_filed_at=now())
> - PostgreSQL `nexus_kyc.kyc_audit` — INSERT (EVENT=SAR_FILED, sarReferenceNumber, filedBy)

### 11. Trigger re-verification
```
POST http://localhost:8091/internal/v1/kyc/re-verify/{userId}
Content-Type: application/json

{
  "reason": "COMPLIANCE_REQUESTED"
}
```
Expected: 202 — { userId, status: "RE_VERIFICATION_QUEUED", reason }

> **Kafka topics:** none — goes through SQS, not Kafka
> **DB affected:**
> - MongoDB `nexus_kyc.kyc_verifications` — INSERT new verification record (status=PENDING, reason=COMPLIANCE_REQUESTED)
> - PostgreSQL `nexus_kyc.kyc_audit` — INSERT (EVENT=RE_VERIFY_REQUESTED, reason)
> - AWS SQS `nexus-kyc-documents-pending` — SendMessage (re-verification job)
