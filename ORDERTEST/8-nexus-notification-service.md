# 8 — nexus-notification-service
**Port:** 8089 | **Gateway base:** http://localhost:8080

## External Dependencies
- MongoDB (port 27018) — notification storage + preferences
- Redis (port 6380) — unread count cache + preferences cache
- Kafka (port 19092) — consumes notification trigger events

## Kafka Events Consumed (this is how all notifications are triggered)
| Topic | Published by | Notification type |
|---|---|---|
| `users.registered` | nexus-identity-service (Debezium) | Welcome / onboarding |
| `password.reset.requested` | nexus-identity-service (Debezium) | Password reset email |
| `identity.verified` / `identity.rejected` | nexus-identity-service (Debezium) | KYC_STATUS_UPDATE |
| `transactions.completed` | nexus-saga-orchestrator (Debezium) | TRANSACTION_COMPLETED |
| `fraud.flagged` | nexus-fraud-service (Debezium) | FRAUD_ALERT (cannot be disabled) |
| `saga.onboarding.complete` | nexus-saga-orchestrator (Debezium) | Account ready |

REST endpoints below only read/write preferences and stored notifications — they do NOT publish Kafka events.

## Variables to SAVE
- `{notificationId}` — from GET /notifications response

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8089/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** connectivity probe only — no writes

---

### USER-FACING ENDPOINTS

```
Authorization: Bearer {accessToken}
```

### 2. Get unread count
```
GET http://localhost:8080/api/v1/notifications/unread-count
Authorization: Bearer {accessToken}
```
Expected: 200 — { "unreadCount": N }

> **Kafka topics:** none
> **DB affected:**
> - Redis `notifications:unread:{userId}` — GET counter

### 3. Get notification preferences
```
GET http://localhost:8080/api/v1/notifications/preferences
Authorization: Bearer {accessToken}
```
Expected: 200 — preferences object (creates with defaults if first time)

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_notifications.preferences` — findOne WHERE userId (upserts defaults if not found)

### 4. Update notification preferences
```
PUT http://localhost:8080/api/v1/notifications/preferences
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "language": "es",
  "timezone": "America/Mexico_City",
  "globalOptOut": false,
  "inAppConfig": { "enabled": true },
  "pushConfig": { "enabled": true },
  "eventPreferences": {
    "FRAUD_ALERT": { "enabled": true },
    "TRANSACTION_COMPLETED": { "enabled": true },
    "KYC_STATUS_UPDATE": { "enabled": true }
  }
}
```
Expected: 200 — updated preferences
Note: FRAUD_ALERT cannot be set to enabled: false — returns 400.

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_notifications.preferences` — updateOne (full replace of preferences document)
> - Redis `preferences:{userId}` — DEL (invalidate cache so next Kafka event re-reads updated prefs)

### 5. Register push device
```
POST http://localhost:8080/api/v1/notifications/preferences/device
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "deviceToken": "test-device-token-12345",
  "platform": "FCM"
}
```
Expected: 200 — { deviceArn, platform, registeredAt }

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_notifications.device_registrations` — upsert WHERE userId + deviceToken

### 6. Get notifications list — SAVE notificationId
```
GET http://localhost:8080/api/v1/notifications?page=0&size=20
Authorization: Bearer {accessToken}
```
Expected: 200 — paginated notifications
Note: Will be empty until Kafka events are processed. Complete a transaction first.

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_notifications.notifications` — find paginated WHERE userId, sorted by created_at DESC

### 7. Mark one notification as read
```
PATCH http://localhost:8080/api/v1/notifications/{notificationId}/read
Authorization: Bearer {accessToken}
```
Expected: 200

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_notifications.notifications` — updateOne SET read_at=now() WHERE _id
> - Redis `notifications:unread:{userId}` — DECR by 1

### 8. Mark all notifications as read
```
PATCH http://localhost:8080/api/v1/notifications/read-all
Authorization: Bearer {accessToken}
```
Expected: 200

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_notifications.notifications` — updateMany SET read_at=now() WHERE userId AND read_at IS NULL
> - Redis `notifications:unread:{userId}` — SET 0

### 9. Unregister push device
```
DELETE http://localhost:8080/api/v1/notifications/preferences/device/test-device-token-12345
Authorization: Bearer {accessToken}
```
Expected: 200

> **Kafka topics:** none
> **DB affected:**
> - MongoDB `nexus_notifications.device_registrations` — deleteOne WHERE userId + deviceToken
