# src/handler.py
"""
Lambda entry point.

S3 trigger delivers one or more S3 ObjectCreated records per event.
In practice, KYC uploads always produce a single record (one file
per verification attempt), but S3 can batch events so we handle N.

Processing pipeline (per document):
1.  extract_document_metadata  — parse S3 path + object tags
2.  validate_document          — size, content-type pre-checks
3.  run_rekognition_parallel   — text + face detection (concurrent)
4.  evaluate_quality_gates     — apply quality thresholds
5.  build_complete_result      — assemble structured result dict
6.  publish_result_to_sqs      — send to nexus-ai-kyc-service queue
7.  tag_s3_object              — mark for lifecycle management
8.  emit_processing_metrics    — CloudWatch custom metrics

All errors are caught per-document. A failure on document N does
not prevent processing of document N+1. The Lambda always returns
HTTP 200 to S3 (to prevent S3 retrying the trigger).
"""

import hashlib
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError, as_completed
from typing import Optional

import boto3

from src.model.document_metadata import extract_document_metadata
from src.model.result_builder import (
    build_complete_result,
    build_error_result,
    build_rejection_result,
)
from src.publishing.sqs_publisher import publish_result
from src.rekognition.face_detector import detect_faces
from src.rekognition.text_detector import detect_text
from src.utils.logging_config import log
from src.utils.metrics import emit_processing_metrics
from src.validation.document_validator import validate_document
from src.validation.quality_gates import evaluate_quality_gates

s3_client = boto3.client("s3")


def process_kyc_document(event: dict, context) -> dict:
    """Lambda handler — S3 trigger entry point."""
    start = time.monotonic()
    records = event.get("Records", [])

    log.info("KYC Rekognition Lambda invoked",
             record_count=len(records),
             request_id=context.aws_request_id)

    results = []
    for record in records:
        bucket = record["s3"]["bucket"]["name"]
        key = urllib.parse.unquote_plus(
            record["s3"]["object"]["key"])
        size = record["s3"]["object"].get("size", 0)

        try:
            result = _process_document(bucket, key, size)
        except Exception as exc:
            # Enforce the documented guarantee at the top level too:
            # a bug anywhere in the per-document pipeline must not take
            # down the rest of this batch or the whole invocation.
            log.error("Unhandled exception processing document",
                      bucket=bucket, key=key, error=str(exc))
            result = build_error_result(
                _processing_id(bucket, key), None, key, bucket,
                "UNHANDLED_EXCEPTION", str(exc))
            _publish_and_tag(result, bucket, key)
        results.append(result)

    duration_ms = int((time.monotonic() - start) * 1000)
    log.info("Lambda invocation complete",
             processed=len(results),
             duration_ms=duration_ms)

    # Always return 200 to S3 to prevent trigger retries
    return {
        "statusCode": 200,
        "processedCount": len(results),
        "results": [
            {"key": r.get("s3Key"),
             "status": r.get("processingStatus")}
            for r in results
        ],
    }


def _process_document(bucket: str, key: str, size: int) -> dict:
    processing_id = _processing_id(bucket, key)

    log.info("Processing KYC document",
             processing_id=processing_id,
             bucket=bucket, key=key, size=size)

    # ── Step 1: Metadata ───────────────────────────────────
    metadata = extract_document_metadata(bucket, key)
    if metadata is None:
        result = build_error_result(
            processing_id, None, key, bucket,
            "METADATA_EXTRACTION_FAILED",
            "Could not extract userId/verificationId from S3 path",
        )
        _publish_and_tag(result, bucket, key)
        return result

    # ── Step 2: Pre-validation ─────────────────────────────
    validation = validate_document(bucket, key, size)
    if not validation.valid:
        log.warning("Pre-validation rejected document",
                    processing_id=processing_id,
                    reasons=validation.reasons)
        result = build_rejection_result(
            processing_id, metadata, validation.reasons)
        _publish_and_tag(result, bucket, key)
        return result

    # ── Step 3: Parallel Rekognition ───────────────────────
    rekog_start = time.monotonic()

    text_result: Optional[dict] = None
    face_result: Optional[dict] = None
    text_error: Optional[str] = None
    face_error: Optional[str] = None

    with ThreadPoolExecutor(max_workers=2) as pool:
        text_future = pool.submit(detect_text, bucket, key)
        face_future = pool.submit(detect_faces, bucket, key)

        try:
            for future in as_completed([text_future, face_future],
                                       timeout=25):
                try:
                    result_or_none = future.result()
                except Exception as exc:
                    result_or_none = None
                    log.error("Rekognition call raised unexpectedly",
                              processing_id=processing_id,
                              error=str(exc))

                if future is text_future:
                    if result_or_none is None:
                        text_error = "detect_text returned None"
                    else:
                        text_result = result_or_none
                else:
                    if result_or_none is None:
                        face_error = "detect_faces returned None"
                    else:
                        face_result = result_or_none

        except FutureTimeoutError:
            # One or both Rekognition calls exceeded 25s (throttling /
            # latency spike). Don't let this crash the whole invocation
            # (and the rest of the S3 event batch with it) - record
            # whichever side never finished as an error and proceed
            # with whatever did complete. The still-running thread is
            # abandoned; the `with` block above will block on it during
            # shutdown, same as it always would have.
            if text_result is None and text_error is None:
                text_error = "detect_text timed out after 25s"
            if face_result is None and face_error is None:
                face_error = "detect_faces timed out after 25s"
            log.error("Rekognition parallel calls timed out",
                      processing_id=processing_id,
                      text_done=text_future.done(),
                      face_done=face_future.done())

    rekog_ms = int((time.monotonic() - rekog_start) * 1000)
    log.info("Rekognition parallel calls complete",
             processing_id=processing_id,
             duration_ms=rekog_ms,
             text_ok=text_result is not None,
             face_ok=face_result is not None)

    # ── Step 4: Quality gates ──────────────────────────────
    quality = evaluate_quality_gates(
        text_result, face_result, text_error, face_error)

    # ── Step 5: Build result ───────────────────────────────
    complete = build_complete_result(
        processing_id, metadata,
        text_result, face_result,
        text_error, face_error,
        quality)

    # ── Step 6 + 7: Publish + Tag ──────────────────────────
    _publish_and_tag(complete, bucket, key)

    # ── Step 8: Metrics ────────────────────────────────────
    emit_processing_metrics(quality, metadata, rekog_ms)

    log.info("Document processing complete",
             processing_id=processing_id,
             status=complete["processingStatus"],
             quality_tier=quality["qualityTier"],
             text_lines=complete.get("detectedTextCount", 0),
             face_detected=complete.get("faceDetected", False),
             rekognition_ms=rekog_ms)

    return complete


def _publish_and_tag(result: dict, bucket: str, key: str) -> None:
    """Publish result to SQS + tag S3 object. Both are best-effort."""
    try:
        publish_result(result)
    except Exception as exc:
        log.error("SQS publish failed",
                  key=key, error=str(exc))

    try:
        s3_client.put_object_tagging(
            Bucket=bucket,
            Key=key,
            Tagging={
                "TagSet": [
                    {"Key": "ProcessingStatus",
                     "Value": result.get("processingStatus", "UNKNOWN")},
                    {"Key": "ProcessingId",
                     "Value": result.get("processingId", "")},
                ]
            },
        )
    except Exception as exc:
        # Tagging failure is non-fatal — only affects lifecycle rules
        log.warning("S3 object tagging failed",
                    key=key, error=str(exc))


def _processing_id(bucket: str, key: str) -> str:
    """Deterministic ID: same S3 object always → same processingId."""
    raw = f"{bucket}/{key}".encode()
    return hashlib.sha256(raw).hexdigest()[:16]