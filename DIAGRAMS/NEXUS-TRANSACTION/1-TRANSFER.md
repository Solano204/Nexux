```mermaid
sequenceDiagram
    autonumber
    actor Client as 🧑‍💻 Client
    participant GW as 🟢 API Gateway
    participant TX as 🔵 Transaction Service
    participant AC as 🔵 Account Service
    participant PG as 🟣 PostgreSQL
    participant ZP as 🟠 Zipkin
    participant K as 🟡 Kafka
    participant FR as 🔴 Fraud Service
    participant SO as 🟢 Saga Orchestrator
    participant AUD as 🟠 audit-write-native
    participant ES as 🟣 Elasticsearch

    rect rgb(200, 220, 255)
        Note over Client,GW: ═══ STEP 1: Initiate Transfer ═══
        Client->>+GW: POST /api/v1/transactions/transfer<br/>{ sourceAccountId, targetAccountId, amount, currency, description }
        GW->>GW: verify JWT → set X-User-Id
        GW->>+ZP: open trace span
        GW->>+TX: forward + X-User-Id + X-Device-Fingerprint
    end

    rect rgb(255, 200, 200)
        Note over TX,AC: ═══ STEP 2: Pre-validation ═══
        TX->>TX: extract userId, clientIp, deviceFingerprint
        TX->>+AC: GET /internal/api/v1/accounts/{sourceAccountId}/balance-check
        AC-->>-TX: { availableBalance, status, dailyLimitRemaining }
        TX->>TX: validate: balance >= amount, account ACTIVE, within daily limit
        TX->>+PG: INSERT INTO transactions { txnId, status: INITIATED, sagaId, ... }
        PG-->>-TX: txnId
    end

    rect rgb(200, 255, 200)
        Note over TX,GW: ═══ STEP 3: 202 Accepted ═══
        TX->>+ZP: close span
        ZP-->>-TX: ok
        TX-->>GW: 202 TransactionResponse { txnId, status: INITIATED, amount, currency }
        GW-->>-Client: 202 Accepted — transaction is being processed async
    end

    rect rgb(255, 255, 200)
        Note over TX,K: ═══ STEP 4: Publish to Kafka ═══
        TX->>+K: PUBLISH ► transactions.initiated { txnId, sourceAccountId, targetAccountId, amount, userId }
        K-->>-TX: ack
    end

    rect rgb(230, 230, 255)
        Note over K,ES: ═══ STEP 5: Saga + Fraud + Audit Consumers ═══
        par Consumer 1 — Saga Orchestrator
            K->>+SO: transactions.initiated
            SO->>SO: start TransferSaga<br/>Step 1: FRAUD_CHECKING
            SO->>+K: PUBLISH ► saga.commands.fraud.check { txnId, userId, amount }
            K-->>-SO: ack
        and Consumer 2 — Fraud Service
            K->>+FR: saga.commands.fraud.check
            FR->>FR: AI fraud analysis (OpenAI tools)
            FR->>+K: PUBLISH ► saga.replies.fraud.result { txnId, decision: APPROVE|REJECT|REVIEW }
            K-->>-FR: ack
        and Consumer 3 — audit-write-native
            K->>+AUD: transactions.initiated
            AUD->>+ES: POST /nexus-audit/_doc
            ES-->>-AUD: 201 indexed
        end
    end

    rect rgb(255, 240, 200)
        Note over Client,ES: ✅ TRANSFER INITIATED — saga running async. Poll GET /transactions/{txnId} for status
    end
```
