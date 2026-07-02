```mermaid
sequenceDiagram
    autonumber
    actor Client as 🧑‍💻 Client
    participant GW as 🟢 API Gateway
    participant AC as 🔵 Account Service
    participant RD as 🔴 Redis
    participant PG as 🟣 PostgreSQL

    rect rgb(200, 220, 255)
        Note over Client,GW: ═══ STEP 1: Balance Request ═══
        Client->>+GW: GET /api/v1/accounts/{accountId}/balance
        GW->>+AC: GET /api/v1/accounts/{accountId}/balance
    end

    rect rgb(255, 200, 200)
        Note over AC,RD: ═══ STEP 2: Redis Cache ONLY ═══
        Note over AC: Balance endpoint reads ONLY from Redis cache<br/>No PostgreSQL call — designed for high-frequency polling
        AC->>+RD: GET balance:{accountId}
        RD-->>-AC: cached balance (or null on cache miss)
    end

    alt Cache HIT
        rect rgb(200, 255, 200)
            Note over AC,GW: ═══ STEP 3a: Return Cached Balance ═══
            AC-->>GW: 200 { accountId, availableBalance, currency, cachedAt }
            GW-->>-Client: 200 balance (sub-millisecond response)
        end
    else Cache MISS
        rect rgb(255, 200, 200)
            Note over AC,PG: ═══ STEP 3b: Cache Warming (background) ═══
            AC->>+PG: SELECT availableBalance FROM accounts WHERE accountId = ?
            PG-->>-AC: balance
            AC->>+RD: SET balance:{accountId} TTL 30s
            RD-->>-AC: ok
            AC-->>GW: 503 { error: BALANCE_CACHE_WARMING }<br/>Retry-After: 1
            GW-->>-Client: 503 — retry in 1 second
        end
    end

    rect rgb(255, 240, 200)
        Note over Client,RD: ✅ BALANCE SERVED FROM REDIS — if 503 received, retry after 1s
    end
```
