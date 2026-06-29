# nexus-identity-service
## POST /api/v1/auth/register

```
Trigger legend
──────────────────────────────────────────────────────────────
[HTTP]      client → API Gateway → service (synchronous REST)
[CDC]       Debezium reads outbox table WAL → publishes to Kafka
[KAFKA-L]   service has @KafkaListener always running on that topic
            message arrives → listener method fires
──────────────────────────────────────────────────────────────


═══════════════════════════════════════════════════════════════
Step 1
Service:  nexus-api-gateway:8080 → nexus-identity-service:8083
Trigger:  [HTTP] client POSTs /api/v1/auth/register
DB:       nexus_identity (PostgreSQL)
═══════════════════════════════════════════════════════════════
Duplicate checks (both query DB before writing):
  userRepository.existsByEmailIgnoreCase()  → throws 409 if found
  userRepository.existsByPhoneNumber()      → throws 409 if found

BCrypt hash — timed via Micrometer (identity.bcrypt.duration):
  passwordHash = bcrypt.encode(request.password())   (~100–300ms)

users — INSERT
  user_id       = <new UUID>
  email         = request.email().toLowerCase()
  phone_number  = request.phoneNumber()
  password_hash = <bcrypt>
  full_name     = request.fullName()
  date_of_birth = request.dateOfBirth()
  country       = request.country()
  status        = PENDING_KYC                ← cannot transact until KYC done
  roles         = ["USER"]
  created_at    = NOW()
  deleted_at    = NULL

password_history — INSERT
  history_id    = <new UUID>
  user_id       = <UUID>
  password_hash = <same bcrypt>
  created_at    = NOW()

outbox — INSERT
  aggregate_type = "users.registered"
  aggregate_id   = userId
  event_type     = "UserRegistered"
  topic          = users.registered
  processed_at   = NULL
  payload        = { userId, email, fullName, phoneNumber,
                     country, createdAt, traceId }

  ↓ Debezium reads outbox WAL row → publishes to Kafka
    topic: users.registered
    key:   userId

audit_logs — INSERT
  event_type = USER_REGISTERED
  ip_address = <client IP>
  user_agent = <User-Agent header>
  trace_id   = <traceId>
  details    = { email: "c***@example.com", country }  ← masked

HTTP 201 returned to client immediately.
  { "userId": "<UUID>",
    "message": "Registration successful. Please complete KYC..." }


═══════════════════════════════════════════════════════════════
Step 2
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="users.registered")
          IdentityEventConsumer.consumeUserRegistered() fires
          → OnboardingFlowSagaProcessor.handleUserRegistered()
          consumer group: saga-orchestrator-identity
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
Idempotency guard:
  sagaRepository.findByUserIdAndCompletedAtIsNull(userId)
  → if already exists, skip (safe for Kafka at-least-once)

onboarding_flow_saga_states — INSERT
  saga_id       = <new UUID>
  user_id       = userId
  email         = <from event>
  current_step  = KYC_INITIATED
  attempt_count = 0
  language      = "es"
  completed_at  = NULL               ← stays NULL until KYC resolves

saga_step_history — INSERT  STARTED → KYC_INITIATED

NOTE: No Kafka command is published here.
The orchestrator simply records the saga state and WAITS.
It will be woken up by either identity.verified or identity.rejected
once the user submits their KYC document (see /kyc/initiate below).


══════════════════════════════════════════════════════════════
REQUEST BODY FOR TESTING
══════════════════════════════════════════════════════════════
POST {{baseUrl}}/api/v1/auth/register

{
  "email": "test.user@example.com",
  "password": "SecurePass123!",
  "phoneNumber": "+52 55 1234 5678",
  "fullName": "Carlos Lopez",
  "dateOfBirth": "1990-06-15",
  "country": "MX"
}

Expected response: HTTP 201
{
  "userId": "<UUID>",
  "message": "Registration successful. Please complete KYC verification to activate your account."
}
```


---


# nexus-identity-service
## POST /api/v1/users/me/kyc/initiate

```
Trigger legend
──────────────────────────────────────────────────────────────
[HTTP]      client → API Gateway → service (synchronous REST)
[CDC]       Debezium reads outbox table WAL → publishes to Kafka
[KAFKA-L]   service has @KafkaListener always running on that topic
            message arrives → listener method fires
[SQS]       Direct AWS SQS publish (NOT via Kafka)
            identity-service → SQS queue → Lambda → Rekognition
[HTTP-CB]   HTTP callback from ai-kyc-service back to identity-service
            (internal network only, not through API Gateway)
──────────────────────────────────────────────────────────────
Requires:  Authorization: Bearer <accessToken>
           X-User-Id: <userId>   (injected by API Gateway from JWT)
Content-Type: multipart/form-data
──────────────────────────────────────────────────────────────


═══════════════════════════════════════════════════════════════
Step 1
Service:  nexus-api-gateway:8080 → nexus-identity-service:8083
Trigger:  [HTTP] client POSTs /api/v1/users/me/kyc/initiate
          multipart/form-data: document (file), documentType,
          fullName, dateOfBirth, documentNumber, nationality?, language?
DB:       nexus_identity (PostgreSQL)  +  Redis  +  AWS S3
═══════════════════════════════════════════════════════════════
Java 25 StructuredTaskScope — two tasks run IN PARALLEL:

  ┌─ Task A (fast ~2ms) ─────────────────────────────────┐
  │ Redis: getKycRetryCount(userId)                       │
  │ if retries >= 3 → throw KycRetryLimitExceededException│
  └───────────────────────────────────────────────────────┘

  ┌─ Task B (slow ~500ms–2s) ────────────────────────────┐
  │ S3: s3Uploader.uploadKycDocument(userId, verificationId, │
  │      documentType, document)                          │
  │ → s3Path = "kyc/<userId>/<verificationId>/doc.jpg"    │
  └───────────────────────────────────────────────────────┘

  scope.join() blocks here.
  If EITHER task fails, scope throws — entire request fails.
  No partial writes if S3 is down or retries exceeded.

kyc_verifications — INSERT
  verification_id   = <new UUID>
  user_id           = userId
  attempt_number    = countAttemptsByUserId(userId) + 1
  document_type     = "INE" / "PASSPORT" / etc.
  document_s3_path  = <s3Path from Task B>
  document_s3_bucket = KYC_DOCUMENTS_BUCKET env var
  final_decision    = NULL                 ← pending
  completed_at      = NULL

users — UPDATE
  status = KYC_IN_PROGRESS               ← was PENDING_KYC

outbox — INSERT
  aggregate_type = "identity.kyc"
  event_type     = "KycInitiated"
  topic          = identity.kyc
  payload        = { userId, verificationId, documentType, s3Path,
                     fullName, dateOfBirth, documentNumber,
                     nationality, language, mimeType, initiatedAt }

  ↓ Debezium reads outbox WAL row → publishes to Kafka
    topic: identity.kyc
    key:   userId

Redis — UPDATE
  kycRetryCount(userId) += 1   (TTL: 30 days)

audit_logs — INSERT
  event_type = KYC_INITIATED
  details    = { verificationId, documentType, attemptNumber }

HTTP 202 returned to client immediately.
  { "verificationId": "<UUID>",
    "message": "KYC verification initiated. You will be notified when complete." }


═══════════════════════════════════════════════════════════════
Step 2  ← PARALLEL to outbox/Kafka path
Service:  nexus-identity-service:8083
Trigger:  Same HTTP handler — DIRECT SQS publish (no Kafka)
          SqsKycPublisher.publishKycDocumentForAnalysis()
External: AWS SQS queue: nexus-kyc-documents
═══════════════════════════════════════════════════════════════
SQS message — SEND
  queueUrl   = KYC_QUEUE_URL env var
  body       = { userId, verificationId, s3Path,
                 documentType, publishedAt }
  attributes = { userId (String), documentType (String) }

NOTE: This is a DIRECT SQS call from identity-service.
      It bypasses Kafka entirely because the Rekognition Lambda
      already listens to this SQS queue. Adding a Kafka→SQS
      bridge would add latency and complexity for no benefit.

      If KYC_QUEUE_URL is blank/missing, the publish is SKIPPED
      with a warn log — no exception thrown. KYC will not proceed.


═══════════════════════════════════════════════════════════════
Step 3
Service:  nexus-kyc-rekognition-lambda (AWS Lambda)
Trigger:  [SQS] Lambda triggered by nexus-kyc-documents queue
External: AWS Rekognition
═══════════════════════════════════════════════════════════════
Lambda pulls document from S3 using s3Path.
Calls Rekognition:
  - DetectText         → extracts text from document image
  - CompareFaces       → optional facial match (if selfie included)
  - DetectModerationLabels → checks for tampered/invalid document

Rekognition results published to:
  SQS queue: nexus-kyc-rekognition-results
  body: { userId, verificationId, rekognitionData, s3Path }


═══════════════════════════════════════════════════════════════
Step 4
Service:  nexus-ai-kyc-service:8091
Trigger:  [SQS] Listener on nexus-kyc-rekognition-results queue
DB:       nexus_kyc (PostgreSQL)  +  OpenAI API
External: AWS S3 (reads document for AI analysis)
═══════════════════════════════════════════════════════════════
AI pipeline runs (~15–30s):
  Stage 1: DataExtractionStage   → extract fields from Rekognition data
  Stage 2: DataComparisonStage   → compare extracted vs. submitted fields
                                   (fullName, dateOfBirth, documentNumber)
  Stage 3: DecisionStage         → approve / reject with reasons

kyc_audit (nexus_kyc DB) — INSERT
  verification_id     = <UUID>
  user_id             = userId
  document_type       = <type>
  ai_decision         = APPROVED / REJECTED
  extracted_data      = { json from AI }
  failure_reasons     = [] or ["NAME_MISMATCH", "EXPIRED_DOCUMENT", ...]
  decided_at          = NOW()

  ↓ ai-kyc-service calls identity-service back via HTTP
    POST /internal/v1/users/{userId}/kyc/result
    body: { verificationId, approved, extractedData,
            verificationDecision, failureReasons }


═══════════════════════════════════════════════════════════════
Step 5  — APPROVED PATH
Service:  nexus-identity-service:8083
Trigger:  [HTTP-CB] POST /internal/v1/users/{userId}/kyc/result
          KycController.receiveKycResult()
          → UserCommandService.processKycResult()
DB:       nexus_identity (PostgreSQL)
═══════════════════════════════════════════════════════════════
kyc_verifications — UPDATE
  ai_extracted_data        = { json }
  ai_verification_decision = { json }
  final_decision           = APPROVED
  decided_at               = NOW()
  completed_at             = NOW()

users — UPDATE
  status = ACTIVE                         ← user can now transact
  kyc_approved_at = NOW()
  (via user.approveKyc())

outbox — INSERT
  event_type = "IdentityVerified"
  topic      = identity.verified
  payload    = { eventType: "IdentityVerified", userId,
                 verificationId, verifiedAt, traceId }

  ↓ Debezium → identity.verified

audit_logs — INSERT
  event_type = KYC_APPROVED
  details    = { verificationId, documentType }

HTTP 200 returned to ai-kyc-service.


═══════════════════════════════════════════════════════════════
Step 6  — APPROVED PATH
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="identity.verified")
          IdentityEventConsumer.consumeKycResult() fires
          → OnboardingFlowSagaProcessor.handleKycApproved()
          consumer group: saga-orchestrator-kyc
DB:       nexus_saga (PostgreSQL)
═══════════════════════════════════════════════════════════════
onboarding_flow_saga_states — UPDATE
  current_step = ACCOUNTS_CREATING

outbox — INSERT
  commandType   = "CreateAccountsCommand"
  targetService = "nexus-account-service"
  topic         = saga.commands
  payload       = { sagaId, userId,
                    accountTypes: ["CHECKING", "SAVINGS"],
                    currency: "MXN" }

  ↓ Debezium → saga.commands
    nexus-account-service creates the user's accounts
    → AccountsCreatedReply → SagaReplyConsumer →
      handleAccountsCreated() → SendWelcomeNotificationCommand →
      nexus-notification-service → COMPLETED


══════════════════════════════════════════════════════════════
Step 5R  — REJECTED PATH
Service:  nexus-identity-service:8083
Trigger:  [HTTP-CB] same /internal/v1/users/{userId}/kyc/result
          result.approved() == false
DB:       nexus_identity (PostgreSQL)
══════════════════════════════════════════════════════════════
kyc_verifications — UPDATE
  final_decision  = REJECTED
  failure_reasons = ["NAME_MISMATCH"] / ["EXPIRED_DOCUMENT"] / etc.
  decided_at      = NOW()
  completed_at    = NOW()

Attempt count check:
  attempts = kycRepository.countAttemptsByUserId(userId)
  permanent = (attempts >= 3)

If NOT permanent (attempts < 3):
  users — UPDATE  status = KYC_REJECTED   (can retry)

If permanent (attempts >= 3):
  users — UPDATE  status = KYC_PERMANENTLY_REJECTED

KycRejectionExplainer — calls OpenAI gpt-4o-mini
  generates user-facing rejection message in Spanish
  e.g. "Tu documento no pudo ser verificado porque el nombre
        no coincide con el registrado. Tienes 2 intentos restantes."

outbox — INSERT
  event_type = "IdentityRejected"
  topic      = identity.rejected
  payload    = { eventType, userId, verificationId,
                 attempt, attemptsRemaining, userMessage,
                 isPermanent, canRetry, failureReasons, rejectedAt }

  ↓ Debezium → identity.rejected

audit_logs — INSERT
  event_type = KYC_REJECTED
  details    = { verificationId, failureReasons, isPermanent }


══════════════════════════════════════════════════════════════
Step 6R  — REJECTED PATH
Service:  nexus-saga-orchestrator:8095
Trigger:  [KAFKA-L] @KafkaListener(topics="identity.rejected")
          IdentityEventConsumer.consumeKycResult() fires
          → OnboardingFlowSagaProcessor.handleKycRejected()
          consumer group: saga-orchestrator-kyc
DB:       nexus_saga (PostgreSQL)
══════════════════════════════════════════════════════════════
onboarding_flow_saga_states — UPDATE
  current_step     = KYC_REJECTED
  failure_reason   = "KYC rejected"
  failure_explanation = { AI-generated explanation }

If canRetry == true:
  completed_at stays NULL           ← saga open; next approval
  current_step = KYC_REJECTED       picks up where it left off

If canRetry == false (3rd rejection):
  current_step = REGISTRATION_CANCELLED
  completed_at = NOW()              ← saga closed permanently


══════════════════════════════════════════════════════════════
FULL ONBOARDING STATE MACHINE (happy path)
══════════════════════════════════════════════════════════════
  /register
    → onboarding_saga: KYC_INITIATED
  /kyc/initiate → Rekognition → ai-kyc → approved
    → onboarding_saga: ACCOUNTS_CREATING
    → account-service: AccountsCreatedReply
    → onboarding_saga: ACCOUNTS_CREATED
    → notification-service: WelcomeNotificationSentReply
    → onboarding_saga: COMPLETED
    → users.status: ACTIVE


══════════════════════════════════════════════════════════════
REQUEST FOR TESTING
══════════════════════════════════════════════════════════════
POST {{baseUrl}}/api/v1/users/me/kyc/initiate
Authorization: Bearer <accessToken>
X-User-Id: <userId>
Content-Type: multipart/form-data

Fields:
  document       = <file: INE/passport image>
  documentType   = "INE"
  fullName       = "Carlos Lopez"
  dateOfBirth    = "1990-06-15"
  documentNumber = "LOPC900615HMCXXX00"
  nationality    = "MX"           (optional)
  language       = "es"           (optional — controls AI rejection messages)

Expected response: HTTP 202
{
  "verificationId": "<UUID>",
  "message": "KYC verification initiated. You will be notified when complete."
}

NOTE: KYC_QUEUE_URL must be set in .env for Steps 3–4 to fire.
      Without it, the SQS publish is skipped silently and the
      verification will remain in PENDING state indefinitely.
```
