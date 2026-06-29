```mermaid
sequenceDiagram
    autonumber
    actor Client as 🧑‍💻 Client
    participant GW as 🟢 API Gateway
    participant NOT as 🔵 Notification Service
    participant MDB as 🟢 MongoDB
    participant RD as 🔴 Redis

    rect rgb(200, 220, 255)
        Note over Client,GW: ═══ STEP 1: Mark Single Notification Read ═══
        Client->>+GW: PATCH /api/v1/notifications/{notificationId}/read<br/>Authorization: Bearer {accessToken}
        GW->>GW: verify JWT → set X-User-Id
        GW->>+NOT: forward request
    end

    rect rgb(255, 200, 200)
        Note over NOT,RD: ═══ STEP 2: Update + Decrement Counter ═══
        NOT->>NOT: extract userId from X-User-Id header
        NOT->>+MDB: findByNotificationIdAndUserId(notificationId, userId)
        MDB-->>-NOT: notification (or null → no-op)
        NOT->>+MDB: UPDATE { isRead: true, readAt: NOW() }
        MDB-->>-NOT: ok
        NOT->>+RD: DECR unread_count:{userId}
        RD-->>-NOT: newCount
    end

    rect rgb(200, 255, 200)
        Note over NOT,GW: ═══ STEP 3: Response ═══
        NOT-->>GW: 200 (no body)
        GW-->>-Client: 200 — notification marked as read
    end

    rect rgb(255, 240, 200)
        Note over Client,RD: ✅ MARKED READ — MongoDB updated, Redis counter decremented
    end
```
