# Logout Flow — Docker + AWS

## Entry Point
```
App → POST /api/v1/auth/logout → localhost:8080
```

---

## Logout — Docker only (always runs regardless of AWS)

```
Mobile/Web App
      │
      │ POST /api/v1/auth/logout
      │ Authorization: Bearer <JWT>
      ▼
┌─────────────────────────────────────────────────────┐
│  nexus-api-gateway :8080  (Docker)                  │
│  Routes to nexus-identity-service                   │
└──────────────────────────┬──────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────┐
│  nexus-identity-service :8083  (Docker)             │
│  AuthController.logout()                            │
│                                                     │
│  1. Decodes JWT (no signature check — reads only)   │
│     extracts: userId, jti, expiresAt                │
│                                                     │
│  2. Marks session INACTIVE in PostgreSQL            │
│     sessions table → session.deactivate()           │
│                                                     │
│  3. Writes JTI to Redis blacklist                   │
│     key: jwt:blacklist:{jti}                        │
│     TTL: remaining token lifetime (auto-expires)    │
│     NX flag: idempotent (safe to call twice)        │
│                                                     │
│  4. Publishes to Redis pub/sub                      │
│     channel: jwt:revoked → {jti}                    │
│     → API Gateway updates in-memory cache NOW       │
│                                                     │
│  5. Invalidates session cache in Redis              │
│     key: session:{userId} deleted                   │
│                                                     │
│  6. Writes audit log (LOGOUT event + IP)            │
│                                                     │
│  7. Clears refreshToken cookie                      │
│     Set-Cookie: refreshToken=; Max-Age=0            │
└──────────────────────────┬──────────────────────────┘
                           │
                           ▼
              200 { message: "Logged out" }
              + Set-Cookie: refreshToken=; Max-Age=0
```

From this point any request using the old JWT hits:
```
nexus-api-gateway → Redis: jwt:blacklist:{jti} → exists → 401 Unauthorized
```
Token never reaches any service.

---

## Additional step with auth-lambda deployed (Cognito flow)

```
Mobile/Web App
      │
      │ POST /auth/revoke   ← AWS API Gateway endpoint
      │ Authorization: Bearer <CognitoJWT>
      ▼
┌─────────────────────────────────────────────────────┐
│  nexus-auth-lambda  (AWS)                           │
│  TokenRevocationHandler.handle()                    │
│                                                     │
│  1. Validates Cognito JWT signature (JWKS)          │
│  2. Writes JTI to DynamoDB nexus-revoked-tokens     │
│     key: REVOKED#{jti}   TTL: token expiry          │
│  3. Invalidates session in DynamoDB nexus-sessions  │
└─────────────────────────────────────────────────────┘
```

---

## Revocation stores comparison

| Store | Key | TTL | Survives restart |
|---|---|---|---|
| Redis blacklist (Docker) | `jwt:blacklist:{jti}` | Token remaining lifetime | No |
| DynamoDB (AWS Lambda) | `REVOKED#{jti}` | Token expiry (TTL field) | Yes — permanent |

---

## Hard vs Soft logout

| Scenario | Docker only | + Auth Lambda |
|---|---|---|
| Token blacklisted immediately | Yes (Redis pub/sub) | Yes (DynamoDB write) |
| Blacklist survives Redis restart | No — token briefly valid again | Yes — DynamoDB is permanent |
| Stolen token killed | Until Redis recovers | Permanently |
| Revocation works multi-region | No | Yes (DynamoDB global tables) |
| Session cleared | PostgreSQL + Redis | PostgreSQL + Redis + DynamoDB |
