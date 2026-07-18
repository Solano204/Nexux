# NEXUS API — Authentication

## The short version

1. `POST /api/v1/auth/register`, then `POST /api/v1/auth/login` against
   `nexus-api-gateway` (`:8080`) → get back a JWT `accessToken`.
2. Send it as `Authorization: Bearer <token>` on every other request,
   still against the gateway.
3. The gateway validates the JWT and forwards your request to whichever
   service handles it, with an `X-User-Id` header it sets itself —
   downstream services never see your token and never re-validate it.

Full copy-pasteable version of steps 1-2: [01_QUICKSTART.md](01_QUICKSTART.md).

## Why this two-layer model exists

`nexus-api-gateway` is the **only** JWT validator in the platform
(`JwtAuthenticationFilter`, RS256, checked against a Redis-backed
revocation blacklist on every request). Every other service trusts the
`X-User-Id` header the gateway sets *after* that validation — they don't
have JWT-verification code at all. This is a deliberate architecture
choice (see
`CHANGES-BESTPRACTICES/10_ARCHITECTURE_PATTERNS_CHANGES.md`, Sección 3),
not a shortcut: one validator means one place to get JWT handling right,
one place to rotate signing keys, one place to check the revocation list.

**This matters for where you test.** If you open a service's own Swagger
UI directly (e.g. `http://localhost:8085/swagger-ui.html` for
account-service) instead of going through the gateway, sending a Bearer
JWT there does nothing — that service doesn't check it. Its "Authorize"
button asks for `X-User-Id` instead, because that's what the service
itself actually validates. Set it to any UUID for local testing; in
production it's never set by a client, only by the gateway.

| | Via gateway (`:8080`) | Direct to a service (`:8085`, `:8086`, ...) |
|---|---|---|
| What you send | `Authorization: Bearer <JWT>` | `X-User-Id: <uuid>` |
| Who validates it | Gateway (signature, expiry, revocation) | Nobody — the header is trusted as-is |
| When to use it | Always, for anything resembling real usage | Only local dev against a single service's Swagger UI |

## Token lifecycle

- **Access token**: 1 hour, returned in the login response body.
- **Refresh token**: 30 days, returned as an `HttpOnly` `Secure` cookie
  scoped to `/api/v1/auth/refresh-token` — never in the response body,
  so it isn't reachable by XSS. Exchange it via
  `POST /api/v1/auth/refresh-token` (cookie sent automatically by a
  browser; with `curl` you need `-b cookies.txt` from a login that
  captured it with `-c cookies.txt`).
- **Logout**: `POST /api/v1/auth/logout` with the access token in
  `Authorization` — decodes the token, blacklists its `jti` (revocation
  ID), clears the refresh cookie.

## Authentication errors

All of these come from `nexus-api-gateway`'s `JwtAuthenticationFilter`,
**before** your request ever reaches a service — the error format here
is gateway-specific (not the RFC 9457 `ProblemDetail` shape the services
themselves return, see [03_ERRORS.md](03_ERRORS.md)):

```json
{
  "error": "INVALID_TOKEN",
  "message": "Token is invalid, expired, or has a bad signature",
  "timestamp": "2026-07-18T14:32:01Z",
  "path": "/api/v1/accounts"
}
```

| HTTP status | `error` code | When | What to do |
|---|---|---|---|
| 401 | `MISSING_TOKEN` | No `Authorization` header, or it doesn't start with `Bearer ` | Add the header |
| 401 | `INVALID_TOKEN` | Bad signature, expired, wrong issuer | Log in again |
| 401 | `TOKEN_REVOKED` | Token's `jti` is in the Redis revocation blacklist (already logged out) | Log in again |
| 403 | `ACCOUNT_SUSPENDED` | Token is valid but the account's `accountStatus` claim is `SUSPENDED` | Contact support — this isn't a token problem |

**A note on `403 ACCOUNT_SUSPENDED` vs a service's own `403`s**: the
gateway's `403` means your *account* is suspended platform-wide. A `403`
returned by a service itself (e.g. account-service's
`ACCESS_DENIED` — see [03_ERRORS.md](03_ERRORS.md)) means your account
is fine but you don't own the specific resource you asked for. Same HTTP
status, different meaning — check the `error`/`errorCode` field, not
just the status code.

No `WWW-Authenticate` header is sent on `401` responses — deliberate,
per the filter's own comment: it prevents browsers from popping up a
native basic-auth dialog for what is actually an API client.
