# nexus-notification-service — Complete Dependency Map

## Port: 8089

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                              |
|-------------------|-------|------------------------------------------------------------------|
| MongoDB           | 27019 | nexus_notification — notifications collection, preferences       |
| Redis             | 6380  | deduplication cache, unread count cache                         |
| Kafka             | 19092 | consumes 9 topics covering the full platform event stream       |
| Config service    | 8888  | loads config on startup                                         |
| Discovery service | 8761  | Eureka registration                                             |

**NO PostgreSQL, NO Elasticsearch, NO S3, NO SQS, NO OpenAI directly**

---

## Kafka topics CONSUMED

| Topic                 | Consumer class            | What triggers it                             |
|-----------------------|---------------------------|----------------------------------------------|
| saga.commands         | SagaCommandConsumer       | SendWelcomeNotificationCommand (onboarding)  |
| users.registered      | IdentityEventConsumer     | user registration confirmation email        |
| identity.verified     | IdentityEventConsumer     | KYC approved notification                   |
| identity.rejected     | IdentityEventConsumer     | KYC rejected notification                   |
| accounts.created      | IdentityEventConsumer     | account created notification                |
| identity.events       | IdentityEventsConsumer    | login alerts, password change, suspicious activity |
| transactions.completed | TransactionEventConsumer | transaction success notification             |
| transactions.failed   | TransactionEventConsumer  | transaction failed / fraud notification     |
| fraud.flagged         | FraudEventConsumer        | fraud alert push notification               |

---

## Kafka topics PRODUCED

| Topic        | Consumed by          | When                                    |
|--------------|----------------------|-----------------------------------------|
| saga.replies | saga-orchestrator    | NotificationSentReply (originalCommand field distinguishes type) |

---

## Public endpoints (require JWT via gateway)

| Method | Path                                          | Returns                       |
|--------|-----------------------------------------------|-------------------------------|
| GET    | /api/v1/notifications                         | paginated list of notifications|
| GET    | /api/v1/notifications/unread-count            | integer count                 |
| GET    | /api/v1/notifications/preferences             | notification preferences      |
| PUT    | /api/v1/notifications/preferences             | update preferences            |
| POST   | /api/v1/notifications/preferences/device      | register device token (push)  |
| DELETE | /api/v1/notifications/preferences/device/{token} | unregister device           |

---

## Indirect dependencies

| Component | Role                                                        |
|-----------|-------------------------------------------------------------|
| PostgreSQL | NOT used                                                   |
| Elasticsearch | NOT used directly — audit-write writes notification events |
| S3 / SQS  | NOT used                                                    |
| OpenAI    | NOT used                                                    |

---

## Notes

- The saga fix from testing: NotificationSentReply always sends replyType="NotificationSentReply" with originalCommand field — saga-orchestrator routes by originalCommand to distinguish welcome vs transfer notifications
- Email delivery is done inline (no external email provider configured) — add SMTP/SES config for real delivery
- Gateway route exists for /api/v1/notifications/** with JwtAuthentication
