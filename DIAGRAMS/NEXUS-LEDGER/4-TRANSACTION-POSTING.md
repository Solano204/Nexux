```mermaid
sequenceDiagram
    autonumber
    actor Client as 🧑‍💻 Client
    participant GW as 🟢 API Gateway
    participant LD as 🟣 Ledger Service
    participant PG as 🟣 PostgreSQL

    rect rgb(200, 220, 255)
        Note over Client,GW: ═══ STEP 1: Get Posting Detail ═══
        Client->>+GW: GET /api/v1/ledger/transactions/{transactionId}/posting<br/>Authorization: Bearer {accessToken}
        GW->>GW: verify JWT
        GW->>+LD: forward request
    end

    rect rgb(255, 200, 200)
        Note over LD,PG: ═══ STEP 2: Lookup Posting ═══
        Note over PG: A posting = double-entry record for a transaction<br/>Contains DEBIT entry (source) + CREDIT entry (target)
        LD->>+PG: SELECT p.*, e1.*, e2.*<br/>FROM ledger_postings p<br/>JOIN ledger_entries e1 ON e1.postingId = p.postingId AND e1.type = 'DEBIT'<br/>JOIN ledger_entries e2 ON e2.postingId = p.postingId AND e2.type = 'CREDIT'<br/>WHERE p.transactionId = ?
        PG-->>-LD: posting detail (or null)
    end

    alt posting found
        rect rgb(200, 255, 200)
            Note over LD,GW: ═══ STEP 3a: Return Posting ═══
            LD-->>GW: 200 { postingId, transactionId,<br/>  debitEntry: { accountId, amount },<br/>  creditEntry: { accountId, amount },<br/>  description, postedAt, reversed }
            GW-->>-Client: 200 posting detail
        end
    else not found
        rect rgb(255, 200, 200)
            Note over LD,GW: ═══ STEP 3b: Not Found ═══
            LD-->>GW: 404 Not Found
            GW-->>-Client: 404 — transaction not yet posted to ledger
        end
    end

    rect rgb(255, 240, 200)
        Note over Client,PG: ✅ POSTING DETAIL — shows both sides of the double-entry (DEBIT source + CREDIT target)
    end
```
