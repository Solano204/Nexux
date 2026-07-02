```mermaid
sequenceDiagram
    autonumber
    actor Client as 🧑‍💻 Client
    participant GW as 🟢 API Gateway
    participant ID as 🔵 Identity Service
    participant PG as 🟣 PostgreSQL
    participant RD as 🔴 Redis
    participant ZP as 🟠 Zipkin

    rect rgb(200, 220, 255)
        Note over Client,GW: ═══ STEP 1: Token Refresh Request ═══
        Client->>+GW: POST /api/v1/auth/refresh-token<br/>Cookie: refreshToken={refreshToken}
        Note over GW: refreshToken is HttpOnly — sent automatically by browser<br/>Path-scoped to /api/v1/auth/refresh-token only
        GW->>+ZP: open trace span
        GW->>+ID: forward with cookie header
    end

    rect rgb(255, 200, 200)
        Note over ID,RD: ═══ STEP 2: Validate Refresh Token ═══
        ID->>ID: verify refresh token signature (RS256)
        ID->>+RD: GET blacklist:{refreshJti}
        RD-->>-ID: null (not blacklisted)
        ID->>+PG: SELECT session WHERE sessionId = refreshJti AND active = true
        PG-->>-ID: session record
        alt session valid
            ID->>ID: generate new accessToken (RS256, 15min TTL)
            ID->>ID: rotate refreshToken (new jti, 30d TTL)
            ID->>+RD: SET session:userId:newJti TTL 30d
            RD-->>-ID: ok
            ID->>+RD: DEL session:userId:oldJti
            RD-->>-ID: ok
        else session invalid / expired
            ID-->>GW: 401 { error: REFRESH_TOKEN_EXPIRED }
            GW-->>Client: 401 — force re-login
        end
    end

    rect rgb(200, 255, 200)
        Note over ID,GW: ═══ STEP 3: Return New Tokens ═══
        ID->>+ZP: close span
        ZP-->>-ID: ok
        ID-->>GW: 200 { accessToken, expiresIn }<br/>Set-Cookie: refreshToken={newToken}; HttpOnly; Secure
        GW-->>-Client: 200 — new accessToken in body, rotated refreshToken in cookie
    end

    rect rgb(255, 240, 200)
        Note over Client,ID: ✅ TOKEN ROTATED — old refreshToken invalidated, new pair issued
    end
```
