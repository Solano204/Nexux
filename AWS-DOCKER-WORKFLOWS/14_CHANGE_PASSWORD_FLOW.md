# CHANGE PASSWORD Flow — Docker + AWS

## Endpoint
```
POST /api/v1/users/me/change-password
```
Authenticated — requires valid JWT.

## Entry Point
```
App → POST /api/v1/users/me/change-password → localhost:8080
Headers: Authorization: Bearer <accessToken>
Body: {
  "currentPassword": "OldPass123!",
  "newPassword": "NewPass456!"
}
```

---

## Full Flow

```
App (authenticated user)
 │
 │ POST /api/v1/users/me/change-password
 │ Authorization: Bearer <jwt>
 ▼
┌────────────────────────────────────────┐
│  nexus-api-gateway :8080               │
│                                        │
│  1. Extract JWT from Authorization     │
│  2. Validate signature + expiry        │
│  3. Check Redis jwt:blacklist:{jti}    │
│  4. Add X-User-Id: {userId} header     │
│  5. Route → nexus-identity-service     │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  nexus-identity-service :8083          │
│  UserController.changePassword()       │
│                                        │
│  extractUserId() ← X-User-Id header    │
│  extractCurrentSessionId() ← attribute │
│  calls commandService.changePassword() │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  UserCommandService.changePassword()   │
│  @Transactional                        │
│                                        │
│  1. Load user from PostgreSQL          │
│  2. BCrypt verify currentPassword      │
│     IF wrong → audit + 401             │
│                                        │
│  3. Load last 5 password hashes        │
│     from passwordHistory table         │
│  4. Check new password against each    │
│     IF reused → throw PasswordReused   │
│                                        │
│  5. BCrypt hash newPassword            │
│  6. Update user.passwordHash           │
│  7. Save to passwordHistory            │
│                                        │
│  8. Find ALL active sessions for user  │
│  9. For each session EXCEPT current:   │
│     a. session.deactivate()            │
│     b. Redis blacklist jti (NX flag)   │
│     c. Redis pub/sub jwt:revoked       │
│        → gateway invalidates cache     │
│  10. Save all sessions                 │
│  11. Redis: invalidate session cache   │
│      for userId                        │
│                                        │
│  12. Write Outbox → identity.events    │
│      eventType: PasswordChanged        │
│                                        │
│  13. Audit log → PASSWORD_CHANGED      │
│      details: sessionsRevoked=N        │
└──────────────────┬─────────────────────┘
                   │ Debezium CDC
                   │ Kafka: identity.events
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-service :8089      │
│  Receives PasswordChanged event        │
│  Publishes → SNS: security-alert       │
└──────────────────┬─────────────────────┘
                   │ SNS trigger (AWS only)
                   ▼
┌────────────────────────────────────────┐
│  nexus-notification-dispatcher-lambda  │
│  EMAIL → "Your password was changed"   │
│  "If this wasn't you, contact support" │
│  SMS   → security alert SMS            │
└────────────────────────────────────────┘

App ← HTTP 200 (no body)
```

---

## Session revocation model

After password change:

```
Device A (changed password here)  → KEEPS current session ✓
Device B (logged in elsewhere)    → REVOKED
Device C (remembered login)       → REVOKED
```

Revocation is immediate:
1. JTI stored in `jwt:blacklist:{jti}` with TTL = token remaining lifetime
2. `jwt:revoked` pub/sub fires → gateway clears its in-memory validation cache
3. Next request from Device B/C hits gateway → blacklist check fails → 401

---

## Password reuse check

```
passwordHistory table (last 5 entries, ordered by createdAt DESC)
Each entry: { historyId, userId, passwordHash, createdAt }

BCrypt.matches(newPassword, hash) for each of last 5
→ if any match → throw PasswordReusedException → HTTP 400
```

---

## Security properties

| Property | Implementation |
|---|---|
| Current password verified | BCrypt verify before any change |
| Reuse prevention | Last 5 hashes checked |
| All other sessions invalidated | JTI blacklisted + pub/sub revocation |
| Security notification | Email + SMS sent after change |
| Audit trail | audit_log row + Kafka PasswordChanged event |
