# User Registration Flow — Docker + AWS

## Entry Point
```
App → POST /api/v1/auth/register → localhost:8080
```

---

## Step 1 — API Gateway routes to Identity Service

```
Mobile/Web App
      │
      │ POST /api/v1/auth/register
      │ { email, password, fullName, phone }
      ▼
┌─────────────────────────────────────────────────────┐
│  nexus-api-gateway :8080  (Docker)                  │
│  Routes to nexus-identity-service                   │
└──────────────────────────┬──────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────┐
│  nexus-identity-service :8083  (Docker)             │
│  - Validates input                                  │
│  - Hashes password (BCrypt)                         │
│  - Creates user in PostgreSQL                       │
│  - Signs JWT with local keystore (nexus-identity.jks│
│  - Publishes KYC initiation event to Kafka          │
│  - Returns { accessToken, userId }                  │
└──────────────────────────┬──────────────────────────┘
                           │ Kafka: kyc.initiated
                           ▼
┌─────────────────────────────────────────────────────┐
│  nexus-ai-kyc-service :8091  (Docker)               │
│  - Receives KYC initiation                          │
│  - Creates pending KYC record                       │
│  - Waits for Rekognition result from SQS            │
│  ← STOPS HERE without the KYC lambda deployed      │
└─────────────────────────────────────────────────────┘
```

---

## Step 2 — User uploads ID document for KYC (requires AWS)

```
Mobile/Web App
      │
      │ PUT document to S3 presigned URL
      ▼
┌─────────────────────────────────────────────────────┐
│  AWS S3: nexus-kyc-documents-{accountId}            │
│  key: kyc/{userId}/{verificationId}.jpg             │
└──────────────────────────┬──────────────────────────┘
                           │ S3 ObjectCreated trigger
                           ▼
┌─────────────────────────────────────────────────────┐
│  nexus-kyc-rekognition-lambda  (AWS)                │
│  1. Extract metadata from S3 path                   │
│  2. Validate: size (10KB–10MB), content-type        │
│  3. Parallel Rekognition (25s timeout):             │
│     - DetectText  → reads ID fields                 │
│     - DetectFaces → checks face present             │
│  4. Quality gates:                                  │
│     - brightness  ≥ 40                              │
│     - sharpness   ≥ 40                              │
│     - text elements ≥ 5                             │
│     - text confidence ≥ 70%                         │
│  5. Publishes result to SQS                         │
│  6. Tags S3 object (ProcessingStatus)               │
└──────────────────────────┬──────────────────────────┘
                           │ SQS: nexus-kyc-rekognition-results
                           ▼
┌─────────────────────────────────────────────────────┐
│  nexus-ai-kyc-service :8091  (Docker)               │
│  - Consumes Rekognition result from SQS             │
│  - Makes KYC decision: APPROVED / REJECTED          │
│  - Updates user KYC status in DB                    │
│  - Publishes → Kafka: kyc.completed                 │
└──────────────────────────┬──────────────────────────┘
                           │ Kafka: kyc.completed
                           ▼
┌─────────────────────────────────────────────────────┐
│  nexus-notification-service :8089  (Docker)         │
│  - Receives KYC result                              │
│  - Builds DispatchRequest (email + push)            │
│  - Publishes → SNS: nexus-notification-dispatch     │
└──────────────────────────┬──────────────────────────┘
                           │ SNS trigger
                           ▼
┌─────────────────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  (AWS)        │
│  - Renders Thymeleaf email template                 │
│  - Sends via SES → user inbox                       │
│  - Sends push via SNS → APNs/FCM                   │
│  - Reports delivery → SQS: nexus-delivery-status   │
└─────────────────────────────────────────────────────┘
```

---

## Without AWS vs With AWS

| Step | Docker only | + AWS |
|---|---|---|
| User created | Works | Works |
| JWT issued | Works (local keystore) | Works |
| KYC document scan | Stuck — no Rekognition result | Rekognition runs, result delivered via SQS |
| KYC completion email | Published to SNS, never delivered | SES sends real email to user |
| Account activated | Never (KYC stuck) | After KYC approved |
