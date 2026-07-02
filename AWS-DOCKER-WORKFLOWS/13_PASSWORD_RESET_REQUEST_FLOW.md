# PASSWORD RESET REQUEST Flow — Docker + AWS

## Endpoints
```
POST /api/v1/auth/password-reset/request   ← initiate (covered here)
POST /api/v1/auth/password-reset/confirm   ← complete with token
```

Both are PUBLIC — no JWT required.

## Entry Point
```
App → POST /api/v1/auth/password-reset/request → localhost:8080
Body: {
  "email": "user@example.com"
}
```

---

## Security design

The response is ALWAYS 200, regardless of whether the email is registered.
This prevents user enumeration — an attacker cannot probe which emails exist.

```
email registered   → token created, email sent
email unknown      → silently ignored, same 200 response
infrastructure fail → exception suppressed in AuthController try-catch
```

---

## Full Flow

```
App (unauthenticated user)
 │
 │ POST /api/v1/auth/password-reset/request
 │ {"email": "user@example.com"}
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
│  Route: nexus-identity-service         │
│  (No JWT validation — public endpoint) │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-identity-service :8083          │
│  AuthController.requestPasswordReset() │
│                                        │
│  try {                                 │
│    commandService.requestPasswordReset │
│  } catch (Exception e) {              │
│    log.warn(e) // suppressed          │
│  }                                     │
│  return 200 always                     │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  UserCommandService.requestPasswordReset│
│                                        │
│  1. Normalize email → lowercase/trim   │
│  2. Look up user in PostgreSQL         │
│                                        │
│  IF email not found:                   │
│     log.debug + return (silent)        │
│                                        │
│  IF email found:                       │
│  3. Generate 256-bit token             │
│     SecureRandom.getInstanceStrong()   │
│     Base64.getUrlEncoder().encode(32B) │
│     → ~43 chars, URL-safe              │
│                                        │
│  4. Store in Redis:                    │
│     key:   pwreset:{token}             │
│     value: userId.toString()           │
│     TTL:   30 minutes                  │
│                                        │
│  5. Write Outbox → identity.events     │
│     eventType: PasswordResetRequested  │
│     payload: userId, email, token,     │
│              expiresAt, requestedAt    │
│                                        │
│  6. Write audit log                    │
│     eventType: PASSWORD_RESET_REQUESTED│
└──────────────────┬─────────────────────┘
                   │ Debezium CDC (outbox)
                   │ Kafka: identity.events
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  Receives PasswordResetRequested event │
│  Builds email with reset link:         │
│  https://app.nexus.mx/reset?token=XXX  │
│  Publishes → SNS: nexus-notification-dispatch
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS only)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  channel: EMAIL                        │
│  Provider: AWS SES v2                  │
│  Template: Thymeleaf password-reset    │
│  Content:                              │
│  "Click here to reset your password"  │
│  Link expires in 30 minutes            │
└────────────────────────────────────────┘

App ← HTTP 200 (already returned, fire-and-forget)
{
  "message": "If this email is registered, 
               a password reset link has been sent."
}
```

---

## Confirm flow (completing the reset)

```
User clicks link in email
  └─► POST /api/v1/auth/password-reset/confirm
      Body: { "token": "...", "newPassword": "..." }
       │
       ▼
nexus-identity-service
  1. Resolve token in Redis → get userId
  2. Check new password vs last 5 (reuse prevention)
  3. BCrypt hash new password
  4. Update user.passwordHash
  5. Add to passwordHistory table
  6. Revoke ALL active sessions
     - Each JTI → Redis blacklist
     - Redis pub/sub jwt:revoked → gateway cache invalidation
  7. Delete token from Redis (one-time use)
  8. Outbox → PasswordResetCompleted
  9. Audit log → PASSWORD_RESET_COMPLETED

Token errors:
  - Expired (> 30 min)   → 400 InvalidPasswordResetToken
  - Already used (deleted) → 400 InvalidPasswordResetToken
  - Reused password        → 400 PasswordReused
```

---

## Token storage model

| Store | Key | Value | TTL |
|---|---|---|---|
| Redis | `pwreset:{token}` | `{userId}` | 30 min |
| PostgreSQL audit | — | PASSWORD_RESET_REQUESTED event | permanent |
| PostgreSQL outbox | — | PasswordResetRequested | consumed by Debezium |

---

## What AWS adds

| Without AWS | With AWS |
|---|---|
| Notification published to SNS but never delivered | SES sends actual email to user |
| Reset link in email goes nowhere | User receives real email, can click and reset |
