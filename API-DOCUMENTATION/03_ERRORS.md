# NEXUS API — Errors

## Format: RFC 9457 (Problem Details)

Most services return errors as `application/problem+json`:

```json
{
  "type": "https://nexus.com/errors/insufficient-funds",
  "title": "Insufficient Funds",
  "status": 422,
  "detail": "Available balance 45.00 is less than requested amount 500.00",
  "errorCode": "INSUFFICIENT_FUNDS",
  "timestamp": "2026-07-18T14:32:01Z"
}
```

`type`/`title`/`status`/`detail` are the standard RFC 9457 fields.
`errorCode` and `timestamp` are NEXUS additions on every service that
implements this format — `errorCode` is the stable, machine-matchable
value (parse this, not `title`, if you're branching on error type in
code).

**Known gap, not papered over**: as of this writing, 8 of the platform's
user-facing services return this format consistently
(`identity`, `account`, `transaction`, `ledger`, `notification`,
`analytics`, `ai-assistant`, `audit-query-jvm`). `ai-kyc-service`'s
user-facing `/api/v1/kyc/**` endpoints do not yet have a
`GlobalExceptionHandler` — an unhandled exception there returns Spring
Boot's default whitelabel error shape (`{timestamp, status, error,
path}`), not RFC 9457. `fraud-service` and `risk-scoring-service` and
`saga-orchestrator` have no user-facing endpoints at all — everything
under `/internal/**` — so this doesn't apply to them the same way. See
`CHANGES-BESTPRACTICES/13_REST_API_DESIGN_CHANGES.md`, Sección 3, for
the full audit and what's tracked to close this.

Gateway-level auth errors (`401`/`403` before your request reaches any
service) use a *different*, gateway-specific shape — see
[02_AUTHENTICATION.md](02_AUTHENTICATION.md), not this format.

## Status code reference

| Status | Meaning on NEXUS | What to do |
|---|---|---|
| `200` | Success | — |
| `201` | Resource created | `Location` header may point to the new resource |
| `202` | Accepted, not yet settled | Only from `POST /transactions/transfer` and `/payment` — the saga is running, poll `GET /transactions/{id}` for real status |
| `204` | Success, no body | e.g. `DELETE /notifications/preferences/device/{token}` |
| `400` | Request itself is malformed | Fix the request — missing required field, wrong type, failed `@Valid` constraint. Check `fieldErrors` in the body if present |
| `401` | Not authenticated | Missing/invalid/expired JWT (gateway) or missing `X-User-Id` (direct-to-service, dev only) |
| `403` | Authenticated, not authorized | You're logged in, but don't own the resource, lack the role, or your account is suspended — check `errorCode`, don't assume from status alone |
| `404` | Resource doesn't exist, **or you don't own it** | Deliberate: `account-service` and others return `404` rather than `403` for cross-user access to hide whether the resource exists at all, on some endpoints — check each endpoint's own docs (Swagger UI `@ApiResponse` list) for which behavior it uses |
| `409` | Conflict — concurrent modification or duplicate | Safe to retry after a short delay; check for `Retry-After` header |
| `422` | Request was well-formed, but violates a business rule | e.g. insufficient funds, account frozen, daily limit exceeded — not a bug, not retryable without changing the request |
| `429` | Rate limited | See below |
| `500` | Real server-side failure | Not your fault — retry with backoff, or report if persistent |
| `503` | Temporarily unavailable | e.g. account-service's balance-cache warming — check `Retry-After` |

## Rate limiting (`429`)

Enforced at `nexus-api-gateway` via a Redis-backed token bucket
(`RedisRateLimiter`), keyed per-user, with limits tuned per route to the
actual cost of the downstream call (e.g. the AI assistant route allows
far fewer requests/sec than the account route — see
`nexus-api-gateway/src/main/java/.../config/GatewayRoutesConfig.java`
and `application.yml`'s route definitions for the exact numbers per
route). Spring Cloud Gateway's `RedisRateLimiter` includes these response
headers by default (not explicitly disabled anywhere in this codebase):

| Header | Meaning |
|---|---|
| `X-RateLimit-Remaining` | Requests left in the current window |
| `X-RateLimit-Burst-Capacity` | Maximum burst size |
| `X-RateLimit-Replenish-Rate` | Steady-state requests/sec allowed |

A `429` itself doesn't currently carry a `Retry-After` header on this
platform — back off and retry after a second or two, or watch
`X-RateLimit-Remaining` hit `0` on a prior response to anticipate it.

## Account-service business-rule errors (`errorCode` values)

The most-exercised error catalog on the platform — every money-movement
path (transfers, payments, fees) runs through balance checks that raise
these:

| `errorCode` | Status | Meaning |
|---|---|---|
| `INSUFFICIENT_FUNDS` | 422 | Available balance is less than the requested amount |
| `ACCOUNT_FROZEN` | 422 | Account is frozen (fraud hold, compliance, admin action) |
| `DAILY_LIMIT_EXCEEDED` | 422 | Transaction would exceed the account's daily transfer limit |
| `MONTHLY_LIMIT_EXCEEDED` | 422 | Transaction would exceed the account's monthly transfer limit |
| `LOCK_TIMEOUT` | 409 | Account row is locked by a concurrent operation — has `Retry-After` |
| `OPTIMISTIC_LOCK_FAILURE` | 409 | Account was modified between your read and write — re-read and retry |
| `DUPLICATE_RESERVATION` | 409 | A balance reservation already exists for this transaction (idempotency safety net at the DB level) |
| `DATA_INTEGRITY_VIOLATION` | 409 | A DB constraint was violated — generic fallback for constraint violations not covered above |
| `VALIDATION_FAILED` | 400 | `@Valid` constraint failed — see `fieldErrors` in the body |
| `INVALID_ARGUMENT` | 400 | Malformed request that isn't a `@Valid` failure (e.g. bad enum value) |
| `INVALID_STATE` | 422 | Operation not valid for the account's current state |
| `UNAUTHORIZED` | 401 | `X-User-Id` header missing |
| `ACCESS_DENIED` | 403 | Account exists but doesn't belong to the caller |
| `ACCOUNTING_INTEGRITY_VIOLATION` | 500 | **Should never happen in production** — triggers immediate alerting, never exposes internal detail to the client |

## Identity-service errors (`errorCode` values)

| `errorCode` | Status | Meaning |
|---|---|---|
| `EMAIL_EXISTS` | 409 | Email already registered |
| `PHONE_EXISTS` | 409 | Phone number already registered |
| `PASSWORD_REUSE` | 422 | New password matches a recent previous one |
| `INVALID_CREDENTIALS` | 401 | Wrong password **or** unknown email — deliberately identical response for both, to prevent account enumeration |
| `ACCOUNT_SUSPENDED` | 403 | Account suspended — contact support |
| `ACCOUNT_LOCKED` | 403 | Too many failed login attempts — temporary lockout |
| `KYC_RETRY_LIMIT_EXCEEDED` | 403 | Exhausted KYC verification retry attempts |
| `USER_NOT_FOUND` | 404 | No user with this ID |
| `INVALID_RESET_TOKEN` | 400 | Password reset token invalid or expired |
| `DOCUMENT_UPLOAD_FAILED` | 502 | Upstream document storage (S3) failed |

## Per-endpoint error lists

The exhaustive per-endpoint breakdown (which of these apply to which
specific route) lives in each service's own OpenAPI spec — every
`@ApiResponse` on a controller method documents the exact codes that
endpoint can return. This page is the shared catalog; Swagger UI
(`/swagger-ui.html` on each service, once rolled out per
`CHANGES-BESTPRACTICES/14_API_DOCUMENTATION_CHANGES.md`) is where you
check a specific endpoint. As of this writing that's live on
`nexus-account-service` and `nexus-transaction-service` — the pilot
services — with the rest tracked as pending work.
