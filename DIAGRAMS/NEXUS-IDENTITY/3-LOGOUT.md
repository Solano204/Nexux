```mermaid
sequenceDiagram
    autonumber
    actor Client as 🧑‍💻 Client
    participant GW as 🟢 API Gateway
    participant ID as 🔵 Identity Service
    participant RD as 🔴 Redis
    participant ZP as 🟠 Zipkin
    participant K as 🟡 Kafka
    participant AUD as 🟠 audit-write-native
    participant ES as 🟣 Elasticsearch

    rect rgb(200, 220, 255)
        Note over Client,GW: ═══ STEP 1: Logout Request ═══
        Client->>+GW: POST /api/v1/auth/logout<br/>Authorization: Bearer {accessToken}
        GW->>GW: validate JWT → extract X-User-Id, X-Jti
        GW->>+ZP: open trace span
        GW->>+ID: forward + X-User-Id + X-Jti headers
    end

    rect rgb(255, 200, 200)
        Note over ID,RD: ═══ STEP 2: Invalidate Token ═══
        ID->>ID: decode JWT → extract jti (token unique ID)
        ID->>+RD: SET blacklist:jti = 1 TTL 900s (15min token lifetime)
        RD-->>-ID: ok
        ID->>+RD: DEL session:userId:sessionId
        RD-->>-ID: ok
    end

    rect rgb(200, 255, 200)
        Note over ID,GW: ═══ STEP 3: Clear Cookie + Response ═══
        ID->>+ZP: close span
        ZP-->>-ID: ok
        ID-->>GW: 200 { message: "Logged out successfully" }<br/>Set-Cookie: refreshToken=; MaxAge=0; HttpOnly
        GW-->>-Client: 200 — refresh cookie cleared
    end

    rect rgb(255, 255, 200)
        Note over ID,ES: ═══ STEP 4: Async Audit ═══
        ID->>+K: PUBLISH ► users.logout { userId, jti }
        K-->>-ID: ack
        K->>+AUD: users.logout
        AUD->>+ES: POST /nexus-audit/_doc
        ES-->>-AUD: 201 indexed
    end

    rect rgb(255, 240, 200)
        Note over Client,ES: ✅ LOGOUT COMPLETE — JWT blacklisted, session deleted, cookie cleared
    end
```
