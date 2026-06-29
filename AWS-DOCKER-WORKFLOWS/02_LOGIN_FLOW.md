# Login Flow — Docker + AWS

## Entry Point
```
App → POST /api/v1/auth/login → localhost:8080
```

---

## Login (always Docker — lambda NOT involved)

```
Mobile/Web App
      │
      │ POST /api/v1/auth/login
      │ { email, password }
      ▼
┌─────────────────────────────────────────────────────┐
│  nexus-api-gateway :8080  (Docker)                  │
│  Routes to nexus-identity-service                   │
└──────────────────────────┬──────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────┐
│  nexus-identity-service :8083  (Docker)             │
│  - Validates credentials against PostgreSQL         │
│  - BCrypt password verification                     │
│  - Signs JWT with local keystore (nexus-identity.jks│
│  - Creates session record in PostgreSQL             │
│  - Caches session in Redis                          │
│  - Returns:                                         │
│    { accessToken }  in response body                │
│    refreshToken     in HttpOnly cookie              │
└─────────────────────────────────────────────────────┘
```

Login is complete. The auth-lambda is NOT part of this flow.

---

## Every subsequent protected request (JWT validation)

After login the app sends the accessToken on every request.
The API Gateway validates it before routing.

### Option A — Docker only

```
App
 │  Authorization: Bearer <localJWT>
 ▼
nexus-api-gateway :8080
 │
 ├─ Checks Redis: jwt:blacklist:{jti} → not found = valid
 └─ Routes to target service
```

### Option B — With auth-lambda deployed (Cognito flow)

```
App
 │  Authorization: Bearer <cognitoJWT>
 ▼
AWS API Gateway
 │
 ▼
nexus-auth-lambda  POST /auth/validate
 │  - Validates JWT signature against Cognito JWKS
 │  - Checks DynamoDB nexus-revoked-tokens → not found = valid
 │  - Checks DynamoDB nexus-sessions → session active
 │  - Returns { valid: true, userId, kycStatus }
 ▼
nexus-api-gateway :8080
 └─ Routes to target service
```

---

## Token Refresh

### Docker only
```
App
 │  POST /api/v1/auth/refresh-token
 │  Cookie: refreshToken=<token>
 ▼
nexus-identity-service :8083
 - Validates refreshToken from cookie
 - Checks token not blacklisted in Redis
 - Issues new accessToken (local keystore)
 - Returns new { accessToken }
```

### With auth-lambda (Cognito flow)
```
App
 │  POST /auth/refresh
 │  { refreshToken }
 ▼
nexus-auth-lambda  (AWS)
 - Calls Cognito to exchange refreshToken
 - Gets new Cognito accessToken
 - Calls nexus-identity-service via LocalPlaneBridgeClient
   to sync session state
 - Updates DynamoDB nexus-sessions
 - Returns new { accessToken }
```

---

## Auth flow comparison

| Scenario | Docker only | + Auth Lambda |
|---|---|---|
| Login | nexus-identity-service | nexus-identity-service |
| Token issuer | Local keystore | Cognito |
| Token validation | Redis blacklist check | DynamoDB revocation check |
| Token refresh | nexus-identity-service | auth-lambda → Cognito |
| Revocation persistence | Redis (lost on restart) | DynamoDB (permanent) |
| Multi-region support | No | Yes |
| KYC status on token | No | Yes (lambda bridges back) |
