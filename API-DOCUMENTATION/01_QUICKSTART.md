# NEXUS API — Quickstart

Copy-paste this in order. All three calls go through `nexus-api-gateway`
on `:8080` — never call a service's own port (`:8085`, `:8086`, ...)
directly except for local Swagger UI browsing (see
[02_AUTHENTICATION.md](02_AUTHENTICATION.md)).

Assumes the platform is already running (`docker compose -f
docker-compose-prod.yml up -d`) — this doc doesn't cover starting it, see
`HOW_TO_RUN_LOCAL.md` at the repo root for that.

## 1. Register a user

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "dev-quickstart@example.com",
    "password": "correct-horse-battery-staple",
    "fullName": "Ada Lovelace",
    "phoneNumber": "+525512345678",
    "dateOfBirth": "1990-01-01",
    "country": "MX"
  }'
```

`password` needs 12+ characters. `phoneNumber` needs a leading `+` and
country code (E.164). `country` is a 2-letter ISO code.

Response — `201 Created`:

```json
{
  "userId": "3f9a2b1c-8d4e-4a6f-9c2d-1e5f7a8b9c0d",
  "email": "dev-quickstart@example.com",
  "status": "PENDING_KYC"
}
```

`PENDING_KYC` is expected — full account creation happens after identity
verification (see `AWS-DOCKER-WORKFLOWS/01_REGISTRATION_FLOW.md` for that
saga if you need it). You don't need to complete KYC for this quickstart.

## 2. Log in

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "dev-quickstart@example.com",
    "password": "correct-horse-battery-staple"
  }'
```

Response — `200 OK`:

```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
  "expiresIn": 3600,
  "tokenType": "Bearer",
  "userId": "3f9a2b1c-8d4e-4a6f-9c2d-1e5f7a8b9c0d",
  "roles": ["USER"]
}
```

The refresh token is **not** in this body — it's set as an `HttpOnly`
cookie (`Set-Cookie: refreshToken=...`), scoped to
`/api/v1/auth/refresh-token` only, to keep it out of reach of anything
that can read the response body (XSS). If you're testing with `curl`,
add `-c cookies.txt` to capture it, or just re-login when the access
token expires — for local dev that's simpler than juggling the cookie.

`accessToken` is a short-lived (1 hour) JWT. Save it — every other call
needs it.

## 3. Make your first authenticated call

```bash
curl http://localhost:8080/api/v1/accounts \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9..."
```

Response — `200 OK`:

```json
[]
```

An empty array is the **correct** response here — a brand-new user has
no accounts until KYC completes and the onboarding saga creates them.
The point of this step is confirming the token round-trip works: gateway
validated the JWT, forwarded the request with `X-User-Id` set, and
`nexus-account-service` returned a real (empty) response instead of
`401`.

That's the full loop — you're authenticated and able to call the
platform. From here:

- **See real data**: complete KYC via `POST /api/v1/kyc/initiate` (see
  `AWS-DOCKER-WORKFLOWS/01_REGISTRATION_FLOW.md`), which creates default
  accounts once verification clears.
- **Move money**: `POST /api/v1/transactions/transfer` — see
  `nexus-transaction-service`'s Swagger UI
  (`http://localhost:8086/swagger-ui.html`) for the full request shape,
  or the OpenAPI spec at `http://localhost:8086/v3/api-docs`.
- **Every error you'll hit** is documented in
  [03_ERRORS.md](03_ERRORS.md) — read that before assuming something's
  broken.
