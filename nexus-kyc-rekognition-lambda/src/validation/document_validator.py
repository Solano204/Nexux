# src/validation/document_validator.py
"""
Pre-Rekognition validation.

Catches obviously unprocessable files before spending money on
Rekognition API calls:
  - Empty files
  - Files too large (> 10 MB) or too small (< 10 KB)
  - Unsupported content types (PDF, video, etc.)
  - S3 object no longer exists

These checks use s3.head_object — a single lightweight metadata
call, no image download needed.
"""

import os
from typing import NamedTuple

import boto3

from utils.logging_config import log

s3_client = boto3.client("s3")

MAX_BYTES = int(os.environ.get("MAX_DOCUMENT_SIZE_BYTES", 10_485_760))
MIN_BYTES = int(os.environ.get("MIN_DOCUMENT_SIZE_BYTES", 10_000))
SUPPORTED_TYPES = set(
    os.environ.get(
        "SUPPORTED_CONTENT_TYPES",
        "image/jpeg,image/png,image/webp",
    ).split(",")
)


class DocumentValidationResult(NamedTuple):
    valid: bool
    reasons: list[str]
    content_type: str | None


def validate_document(
        bucket: str, key: str, size_from_event: int
) -> DocumentValidationResult:
    """
    Validate the S3 object before sending to Rekognition.
    Returns (valid, [reasons], content_type).
    """
    reasons: list[str] = []
    content_type: str | None = None

    # ── Size from S3 event ─────────────────────────────────
    if size_from_event == 0:
        reasons.append("EMPTY_FILE")
    elif size_from_event > MAX_BYTES:
        reasons.append(
            f"FILE_TOO_LARGE:{size_from_event}bytes_max:{MAX_BYTES}")
    elif size_from_event < MIN_BYTES:
        reasons.append(
            f"FILE_TOO_SMALL:{size_from_event}bytes_min:{MIN_BYTES}")

    # ── Content type from S3 metadata ─────────────────────
    try:
        head = s3_client.head_object(Bucket=bucket, Key=key)
        content_type = head.get("ContentType", "")

        if content_type not in SUPPORTED_TYPES:
            reasons.append(
                f"UNSUPPORTED_CONTENT_TYPE:{content_type}")

        # Cross-check size with S3 (more accurate than event)
        s3_size = head.get("ContentLength", size_from_event)
        if s3_size != size_from_event and s3_size > MAX_BYTES:
            reasons.append(
                f"FILE_TOO_LARGE_S3:{s3_size}bytes")

    except s3_client.exceptions.NoSuchKey:
        reasons.append("OBJECT_NOT_FOUND")
        log.warning("S3 object not found during validation",
                    bucket=bucket, key=key)
    except Exception as exc:
        reasons.append(f"S3_HEAD_FAILED:{type(exc).__name__}")
        log.error("s3.head_object failed",
                  bucket=bucket, key=key, error=str(exc))

    return DocumentValidationResult(
        valid=len(reasons) == 0,
        reasons=reasons,
        content_type=content_type,
    )