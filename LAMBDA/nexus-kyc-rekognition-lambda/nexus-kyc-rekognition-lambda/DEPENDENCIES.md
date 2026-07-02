# nexus-kyc-rekognition-lambda — Complete Dependency & Run Guide

## No source code bugs found

Two things were **missing from the repo**:

| Item | Status |
|------|--------|
| `samconfig.toml` | **NEW** — was missing |
| `.github/workflows/nexus-kyc-rekognition-lambda.yml` | **NEW** — no CI existed |

---

## 1. What this Lambda does

**Trigger:** S3 `ObjectCreated` events on the `nexus-kyc-documents-{AccountId}` bucket, prefix `kyc/`, suffixes `.jpg` and `.png`.  
**Runtime:** Python 3.12, no SnapStart.

**Processing pipeline per document (8 steps):**
1. `extract_document_metadata` — parse S3 key path to get `userId` and `verificationId`
2. `validate_document` — size, content-type pre-checks (fail fast before Rekognition)
3. Parallel Rekognition calls — `detect_text` + `detect_faces` via `ThreadPoolExecutor(2)`
4. `evaluate_quality_gates` — brightness, sharpness, text count, confidence thresholds
5. `build_complete_result` — structured dict with all detection results
6. `publish_result` — send to `nexus-kyc-rekognition-results` SQS queue
7. `s3_client.put_object_tagging` — tag object for S3 lifecycle rules
8. `emit_processing_metrics` — CloudWatch custom metrics

**S3 key format:**
```
kyc/{userId}/{verificationId}/{filename}.jpg
```
`extract_document_metadata()` parses this path. If the key doesn't match the pattern → `METADATA_EXTRACTION_FAILED` result published to SQS.

---

## 2. How this fits in the KYC flow

```
nexus-ai-kyc-service                    nexus-kyc-rekognition-lambda
  │                                              │
  │── uploads doc to S3 ────────────────────────→│ (S3 trigger)
  │                                              │── Rekognition text detection
  │                                              │── Rekognition face detection
  │                                              │── Quality gates
  │←── result in SQS ───────────────────────────│
  │                                              │
  │── continues KYC pipeline                     │
```

`nexus-ai-kyc-service` uploads the document and then polls `nexus-kyc-rekognition-results` SQS queue for the structured result. This Lambda is the Rekognition pre-processing step before GPT-4o Vision runs in `nexus-ai-kyc-service`.

**Why separate Lambda?** AWS Rekognition processes images natively at high speed — no need to download the image to GPT-4o for the extraction step. The Lambda produces a `detectedTextBlocks`, `faceQuality`, `qualityTier` payload that `nexus-ai-kyc-service` enriches with GPT-4o-mini (Stage 2 comparison).

---

## 3. Quality thresholds

| Gate | Default | Notes |
|------|---------|-------|
| Face brightness | ≥ 40 | 0-100, AWS Rekognition score |
| Face sharpness | ≥ 40 | 0-100, AWS Rekognition score |
| Min text elements | ≥ 5 | An INE/passport should have many text fields |
| Min text confidence | ≥ 70% | Per-word Rekognition confidence |
| Max document size | 10 MB | Rekognition API limit |
| Min document size | 10 KB | Reject obviously corrupt files |

**Quality tiers produced:**
- `QUALITY_PASSED` — meets all thresholds, proceed to GPT-4o
- `QUALITY_REJECTED` — fails one or more gates, reject immediately (no AI cost)
- `QUALITY_DEGRADED` — marginal quality, warn but proceed

S3 lifecycle rules delete `QUALITY_PASSED` objects after **1 day** and `QUALITY_REJECTED` after **7 days** (for debugging).

---

## 4. What you need installed

| Tool | Why |
|------|-----|
| Python 3.12 | Local dev |
| AWS SAM CLI | Build and deploy |
| Docker | `sam local invoke`, LocalStack |
| AWS CLI v2 | LocalStack verification, test uploads |

---

## 5. Local development

```bash
# 1. Start LocalStack
docker compose up -d nexus-localstack
# LocalStack will create the S3 bucket + SQS queues automatically

# 2. Upload a test document (LocalStack S3)
aws --endpoint-url=http://localhost:4566 s3 cp /path/to/test-id.jpg \
  s3://nexus-kyc-documents-000000000000/kyc/user-abc123/verification-xyz789/ine_front.jpg

# 3. Run manually (Rekognition calls will fail in LocalStack CE — mock them)
sam build
sam local invoke KycRekognitionLambda \
  -e events/s3-trigger.json \
  --env-vars events/env.json

# 4. Run unit tests (use fixture Rekognition responses — no real AWS calls)
pip install -r requirements.txt pytest moto[s3,sqs,rekognition]
pytest tests/unit/ -v
```

**Note on local Rekognition testing:** LocalStack Community Edition does not fully emulate Rekognition. The unit tests use `tests/fixtures/rekognition_face_response.json` and `rekognition_text_response.json` as pre-recorded API responses — no real Rekognition calls are made in tests.

---

## 6. Deploy to AWS

```bash
sam build
sam deploy                       # dev
sam deploy --config-env staging  # staging
sam deploy --config-env prod     # production
```

The S3 bucket (`nexus-kyc-documents-{AccountId}`) is created by the SAM stack. No pre-creation needed.

---

## 7. Infrastructure created by SAM template

| Resource | Notes |
|----------|-------|
| Lambda | S3-triggered, 256MB, 30s timeout |
| S3 bucket | `nexus-kyc-documents-{AccountId}`, KMS-encrypted, all-public-access blocked |
| SQS `nexus-kyc-rekognition-results` | 5-min visibility, 1-hour retention |
| SQS DLQ | 24-hour retention |
| CW Alarm `nexus-kyc-high-rejection-rate` | >40% rejection rate over 10 min |
| CW Alarm `nexus-kyc-results-queue-depth` | >50 unprocessed results |

---

## 8. Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID_STAGING` | IAM for staging |
| `AWS_SECRET_ACCESS_KEY_STAGING` | — |
| `AWS_ACCESS_KEY_ID_PROD` | IAM for production |
| `AWS_SECRET_ACCESS_KEY_PROD` | — |

IAM permissions needed: Lambda, S3, SQS, Rekognition, CloudWatch, CloudFormation, IAM.
