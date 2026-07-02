# nexus-notification-dispatcher-lambda — Complete Dependency & Run Guide

## No source code bugs found

Two things were **missing from the repo**:

| Item | Status |
|------|--------|
| `samconfig.toml` | **NEW** — was missing |
| `.github/workflows/nexus-notification-dispatcher-lambda.yml` | **NEW** — no CI existed; includes template presence check |

---

## 1. What this Lambda does

**Trigger:** AWS SNS topic `nexus-notification-dispatch`  
**Runtime:** Java 21 with **SnapStart** (~300ms cold start)

**Three delivery channels:**
- **EMAIL** — `EmailDispatcher` → Thymeleaf template rendering → AWS SES v2
- **SMS** — `SmsDispatcher` → AWS SNS direct publish to E.164 phone number
- **PUSH** — `PushDispatcher` → AWS SNS publish to platform endpoint ARN (APNs/FCM)

**After dispatch:** `DeliveryStatusReporter` sends a `DeliveryStatusEvent` to `nexus-delivery-status` SQS queue → consumed by `nexus-notification-service` (Spring Boot) to update notification delivery state in MongoDB.

---

## 2. Email templates (8 + 1 layout)

| Template | Event |
|----------|-------|
| `_layout.html` | Base layout with Nexus Bank branding |
| `transaction-completed.html` | Payment/transfer successful |
| `transaction-failed.html` | Payment/transfer failed |
| `fraud-alert.html` | High-risk transaction detected |
| `account-created.html` | Onboarding — account ready |
| `kyc-approved.html` | Identity verification passed |
| `kyc-rejected.html` | Identity verification failed |
| `login-new-device.html` | New device login detected |
| `generic-notification.html` | Fallback for unmapped event types |

`EmailTemplateEngine` selects the right template based on `eventType` in the `DispatchRequest`, falls back to `generic-notification.html` for unknown types.

**Why Thymeleaf + SnapStart?** Thymeleaf's template engine initialises lazily on first use — this happens during the SnapStart snapshot capture. Every subsequent cold start restores the already-parsed templates, making first-call rendering near-instantaneous.

---

## 3. SnapStart benefit for this service

Notifications arrive in bursts: morning transaction summaries, KYC batch completions, fraud alert clusters. Without SnapStart, the first Lambda in each burst takes 5-15s to initialise Thymeleaf + AWS SDK clients. With SnapStart + 50 reserved concurrency slots: the notification burst is served within 300ms of the first SNS message.

---

## 4. IMPORTANT — SES Prerequisites

**Before deploying to any environment:**

1. **Verify `FromEmailAddress` in SES:**
   ```bash
   aws sesv2 create-email-identity \
     --email-identity notificaciones@nexusbank.com
   ```
   Check your email and click the verification link.

2. **Request SES production access** (remove sandbox mode):
   In sandbox mode, SES can only send to verified addresses.
   AWS Console → SES → Account dashboard → Request production access.

3. **DKIM + SPF** (strongly recommended for deliverability):
   ```bash
   aws sesv2 put-email-identity-dkim-attributes \
     --email-identity notificaciones@nexusbank.com \
     --signing-attributes-origin AWS_SES
   ```
   Add the three CNAME records to your DNS.

---

## 5. Optional — Push notifications setup

To enable push notifications, create SNS Platform Applications:

```bash
# iOS (APNs)
aws sns create-platform-application \
  --name nexus-ios-push \
  --platform APNS \
  --attributes \
    PlatformCredential="$(cat AuthKey_XXXXXXXX.p8)" \
    PlatformPrincipal="TEAM_ID.BUNDLE_ID"

# Android (FCM)
aws sns create-platform-application \
  --name nexus-android-push \
  --platform GCM \
  --attributes PlatformCredential="YOUR_FCM_SERVER_KEY"
```

Then pass the ARNs via `samconfig.toml` `parameter_overrides` or CI secrets.

---

## 6. Local development

```bash
# 1. Start LocalStack
docker compose up -d nexus-localstack

# 2. Build
mvn package -DskipTests

# 3. Test email dispatch
sam local invoke NotificationDispatcherLambda \
  -e events/sns-email-event.json \
  --env-vars events/env.json

# 4. Test SMS dispatch
sam local invoke NotificationDispatcherLambda \
  -e events/sns-sms-event.json \
  --env-vars events/env.json

# 5. Run tests
mvn test
```

---

## 7. Deploy to AWS

```bash
sam build
sam deploy                       # dev (prompts for confirmation)
sam deploy --config-env staging  # staging
sam deploy --config-env prod     # production
```

---

## 8. Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID_STAGING` | IAM for staging |
| `AWS_SECRET_ACCESS_KEY_STAGING` | — |
| `AWS_ACCESS_KEY_ID_PROD` | IAM for production |
| `AWS_SECRET_ACCESS_KEY_PROD` | — |
| `IOS_PLATFORM_APP_ARN_STAGING` | SNS APNs ARN (empty if not using push) |
| `ANDROID_PLATFORM_APP_ARN_STAGING` | SNS FCM ARN (empty if not using push) |
| `IOS_PLATFORM_APP_ARN_PROD` | Production APNs ARN |
| `ANDROID_PLATFORM_APP_ARN_PROD` | Production FCM ARN |

---

## 9. Monitoring

| Alarm | Threshold | What it means |
|-------|-----------|---------------|
| `nexus-notification-high-failure-rate` | ≥50 failures in 5 min | SES or SNS delivery problems |

The `NotificationAuditQueue` captures all FRAUD_ALERT, SECURITY_ALERT, ACCOUNT_FROZEN, and LOGIN_NEW_DEVICE notifications for compliance review. Subscribe this queue to your compliance audit system.
