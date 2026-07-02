```mermaid
sequenceDiagram
    autonumber
    actor Client as 🧑‍💻 Client
    participant GW as 🟢 API Gateway
    participant ID as 🔵 Identity Service
    participant PG as 🟣 PostgreSQL
    participant RD as 🔴 Redis
    participant ZP as 🟠 Zipkin
    participant K as 🟡 Kafka
    participant AUD as 🟠 audit-write-native
    participant ES as 🟣 Elasticsearch

    rect rgb(200, 220, 255)
        Note over Client,GW: ═══ STEP 1: Login Request ═══
        Client->>+GW: POST /api/v1/auth/login { email, password }
        GW->>+ZP: open trace span
        GW->>+ID: forward + X-B3-TraceId, X-Forwarded-For
    end

    rect rgb(255, 200, 200)
        Note over ID,RD: ═══ STEP 2: Credential Validation ═══
        ID->>+PG: SELECT user WHERE email = ?
        PG-->>-ID: user record
        ID->>ID: bcrypt.verify(password, hash)
        alt credentials valid
            ID->>+PG: INSERT INTO sessions (sessionId, userId, ip, userAgent)
            PG-->>-ID: sessionId
            ID->>+RD: SET session:userId:sessionId TTL 24h
            RD-->>-ID: ok
            ID->>ID: sign JWT (RS256, jti=sessionId)
        else invalid credentials
            ID-->>GW: 401 { error: INVALID_CREDENTIALS }
            GW-->>Client: 401 Unauthorized
        end
    end

    rect rgb(200, 255, 200)
        Note over ID,GW: ═══ STEP 3: Response — Cookie + Body ═══
        ID->>+ZP: close span
        ZP-->>-ID: ok
        ID-->>GW: 200 { accessToken, expiresIn, userId, roles }<br/>Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Strict
        GW-->>-Client: 200 accessToken in body<br/>refreshToken in HttpOnly cookie (NOT exposed to JS)
    end

    rect rgb(255, 255, 200)
        Note over ID,K: ═══ STEP 4: Async Audit Event ═══
        ID->>+K: PUBLISH ► users.login.succeeded { userId, ip, userAgent }
        K-->>-ID: ack
    end

    rect rgb(230, 230, 255)
        Note over K,ES: ═══ STEP 5: Audit Consumer ═══
        K->>+AUD: users.login.succeeded
        AUD->>+ES: POST /nexus-audit/_doc
        ES-->>-AUD: 201 indexed
    end

    rect rgb(255, 240, 200)
        Note over Client,ES: ✅ LOGIN COMPLETE — use accessToken as Bearer for all subsequent calls
    end
```
