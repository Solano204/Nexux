```mermaid
sequenceDiagram
    autonumber
    actor Client as 🧑‍💻 Client
    participant GW as 🟢 API Gateway
    participant AC as 🔵 Account Service
    participant MDB as 🟢 MongoDB

    rect rgb(200, 220, 255)
        Note over Client,GW: ═══ STEP 1: Analytics Request ═══
        Client->>+GW: GET /api/v1/accounts/{accountId}/analytics<br/>Authorization: Bearer {accessToken}
        GW->>GW: verify JWT → set X-User-Id
        GW->>+AC: forward request
    end

    rect rgb(255, 200, 200)
        Note over AC,MDB: ═══ STEP 2: MongoDB Analytics Read ═══
        Note over MDB: MongoDB stores pre-aggregated analytics<br/>Updated asynchronously by Kafka Streams consumers
        AC->>+MDB: db.account_analytics.findOne({ accountId: ? })
        MDB-->>-AC: analytics document (or null)
    end

    alt analytics found
        rect rgb(200, 255, 200)
            Note over AC,GW: ═══ STEP 3a: Return Analytics ═══
            AC-->>GW: 200 { spendingByCategory, topMerchants,<br/>monthlyTrend, avgTransactionSize }
            GW-->>-Client: 200 account analytics
        end
    else no analytics yet
        rect rgb(255, 200, 200)
            Note over AC,GW: ═══ STEP 3b: Not Found ═══
            AC-->>GW: 404 Not Found
            GW-->>-Client: 404 — analytics not yet computed (new account)
        end
    end

    rect rgb(255, 240, 200)
        Note over Client,MDB: ✅ ANALYTICS RETURNED — pre-aggregated by Kafka Streams, served from MongoDB
    end
```
