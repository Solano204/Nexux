```mermaid
sequenceDiagram
    autonumber
    actor Client as 🧑‍💻 Client
    participant GW as 🟢 API Gateway
    participant AN as 🟢 Analytics Service
    participant RD as 🔴 Redis
    participant PG as 🟣 PostgreSQL
    participant LLM as 🧠 OpenAI

    rect rgb(200, 220, 255)
        Note over Client,GW: ═══ STEP 1: AI Insights Request ═══
        Client->>+GW: GET /api/v1/analytics/accounts/{accountId}/insights/2026-06?language=es<br/>Authorization: Bearer {accessToken}
        GW->>GW: verify JWT → set X-User-Id
        GW->>+AN: forward request
    end

    rect rgb(255, 200, 200)
        Note over AN,RD: ═══ STEP 2: Cache Check (1 hour TTL) ═══
        AN->>AN: extract userId, parse yearMonth
        AN->>AN: cacheKey = "analytics:insights:{userId}:2026-06"
        AN->>+RD: GET {cacheKey}
        RD-->>-AN: cached insights (or null)
    end

    alt Cache HIT
        rect rgb(200, 255, 200)
            Note over AN,GW: ═══ STEP 3a: Return Cached ═══
            AN-->>GW: 200 List<FinancialInsight> (from cache)
            GW-->>-Client: 200 insights (instant)
        end
    else Cache MISS — generate with AI
        rect rgb(255, 200, 200)
            Note over AN,LLM: ═══ STEP 3b: AI Generation ═══
            AN->>+PG: SELECT spending analytics for userId + yearMonth
            PG-->>-AN: { totalSpent, spendingByCategory, trend, topMerchants }
            AN->>+LLM: "Generate 3-5 actionable financial insights for this user.<br/>Language: es. Data: {analytics}"
            LLM-->>-AN: [ FinancialInsight { title, description, type, priority, savingsAmount } ]
            AN->>+RD: SET {cacheKey} = insights TTL 3600
            RD-->>-AN: ok
        end
        rect rgb(200, 255, 200)
            Note over AN,GW: ═══ STEP 4: Return Generated Insights ═══
            AN-->>GW: 200 List<FinancialInsight>
            GW-->>-Client: 200 AI-generated insights in Spanish
        end
    end

    rect rgb(255, 240, 200)
        Note over Client,LLM: ✅ AI INSIGHTS — Redis-cached (1h TTL), AI-generated in preferred language
    end
```
