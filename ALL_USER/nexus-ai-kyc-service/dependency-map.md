# nexus-ai-kyc-service — Complete Dependency Map

## Port: 8091

---

## Infrastructure REQUIRES (direct)

| Component         | Port  | Why                                                                   |
|-------------------|-------|-----------------------------------------------------------------------|
| PostgreSQL        | 5433  | nexus_kyc DB — kyc_verifications table (Flyway managed)               |
| MongoDB           | 27019 | nexus_kyc — KycDocumentMongoDB (stores full document + AI results)    |
| Kafka             | 19092 | consumes identity.kyc, produces saga.replies + identity events        |
| AWS S3            | HTTPS | stores uploaded ID documents and selfies before Rekognition           |
| AWS Rekognition   | HTTPS | facial comparison between selfie and ID document                      |
| AWS SQS           | HTTPS | async queue for AI analysis jobs                                      |
| OpenAI API        | HTTPS | document OCR analysis + fraud text scoring                            |
| Config service    | 8888  | loads config on startup                                               |
| Discovery service | 8761  | Eureka registration                                                   |

---

## Kafka topics CONSUMED

| Topic        | Group ID             | Sent by          | What it does                               |
|--------------|----------------------|------------------|--------------------------------------------|
| identity.kyc | nexus-ai-kyc-service | identity-service | triggers full KYC pipeline for a user      |

---

## Kafka topics PRODUCED

| Topic              | Consumed by                             | When                             |
|--------------------|-----------------------------------------|----------------------------------|
| saga.replies       | saga-orchestrator (KycApprovedReply / KycRejectedReply) | after AI decision  |
| identity.verified  | notification-service, saga-orchestrator | KYC APPROVED                     |
| identity.rejected  | notification-service, saga-orchestrator | KYC REJECTED                     |

---

## Services that call KYC (it is the dependency)

| Caller           | How                                           | Why                          |
|------------------|-----------------------------------------------|------------------------------|
| identity-service | Kafka identity.kyc                            | initiates KYC on registration|
| identity-service | HTTP POST /internal/v1/users/{id}/kyc/result  | receives decision back        |
| compliance/admin | HTTP internal endpoints                       | review, SAR, re-verify        |

---

## Public endpoints (require JWT via gateway)

| Method | Path                                        | Body / Returns                              |
|--------|---------------------------------------------|---------------------------------------------|
| POST   | /api/v1/kyc/initiate                        | multipart: idDocument + selfie files        |
| GET    | /api/v1/kyc/status/{verificationId}         | verification status + decision              |

---

## Internal endpoints (service-to-service)

| Method | Path                                              | Who calls it           |
|--------|---------------------------------------------------|------------------------|
| GET    | /internal/v1/kyc/verifications/{id}               | identity-service, admin|
| GET    | /internal/v1/kyc/verifications/user/{userId}      | identity-service       |
| GET    | /internal/v1/kyc/verifications/{id}/audit         | compliance             |
| GET    | /internal/v1/kyc/retry-eligibility/{userId}       | identity-service       |
| POST   | /internal/v1/kyc/review/{id}/outcome              | compliance team        |
| POST   | /internal/v1/kyc/verifications/{id}/sar           | compliance (SAR filing)|
| GET    | /internal/v1/kyc/metrics/daily                    | monitoring             |
| POST   | /internal/v1/kyc/re-verify/{userId}               | admin / compliance     |

---

## KYC pipeline flow

```
1. User uploads idDocument + selfie → POST /api/v1/kyc/initiate
2. Files uploaded to AWS S3
3. SQS queues analysis job
4. AWS Rekognition: compares selfie face vs ID document face
5. OpenAI: OCR + fraud text analysis of ID document
6. Decision: APPROVED / REJECTED / MANUAL_REVIEW
7. Result POSTed to identity-service: /internal/v1/users/{userId}/kyc/result
8. Kafka: identity.verified OR identity.rejected published
9. Kafka: saga.replies published with KycApprovedReply/KycRejectedReply
```

---

## Notes

- AWS credentials required: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION, S3 bucket name
- Without real AWS credentials: S3 upload fails → entire KYC pipeline stalls
- Without real OpenAI key: document analysis returns placeholder → may auto-approve or reject
- Gateway has route for /api/v1/kyc/** with JwtAuthentication
- Gateway has no route for /internal/v1/kyc/** — hit port 8091 directly
