# src/model/document_metadata.py
"""
Extracts user context from the S3 object's path and tags.

S3 key format:
  kyc/{userId}/{verificationId}/{filename}.jpg

S3 object tags (set by nexus-identity-service on upload):
  userId          = platform user UUID
  verificationId  = KYC verification attempt UUID
  documentType    = PASSPORT | NATIONAL_ID | DRIVERS_LICENSE
  attemptNumber   = 1 | 2 | 3
  uploadedAt      = ISO-8601 timestamp

If tags are missing, we fall back to parsing the path.
"""

import urllib.parse
from datetime import datetime, timezone
from typing import Optional

import boto3

from utils.logging_config import log

s3_client = boto3.client("s3")


def extract_document_metadata(
        bucket: str, key: str) -> Optional[dict]:
    """
    Returns metadata dict or None if the path is invalid.
    Never raises — all errors are logged and None is returned.
    """
    try:
        parts = key.split("/")
        # Expected: kyc / {userId} / {verificationId} / filename
        if len(parts) < 4 or parts[0] != "kyc":
            log.error(
                "Unexpected S3 key format",
                key=key,
                expected="kyc/{userId}/{verificationId}/filename",
            )
            return None

        user_id = parts[1]
        verification_id = parts[2]
        filename = parts[3]

        # Validate UUID-like structure
        if not user_id or not verification_id:
            log.error("Missing userId or verificationId in path",
                      key=key)
            return None

        # Fetch S3 object tags
        tags = _fetch_object_tags(bucket, key)

        return {
            "userId": tags.get("userId", user_id),
            "verificationId": tags.get("verificationId",
                                       verification_id),
            "documentType": tags.get("documentType", "UNKNOWN"),
            "attemptNumber": int(tags.get("attemptNumber", "1")),
            "filename": filename,
            "s3Bucket": bucket,
            "s3Key": key,
            "uploadedAt": tags.get(
                "uploadedAt",
                datetime.now(timezone.utc).isoformat()),
        }

    except Exception as exc:
        log.error("Failed to extract document metadata",
                  bucket=bucket, key=key, error=str(exc))
        return None


def _fetch_object_tags(bucket: str, key: str) -> dict:
    """Fetch S3 object tags. Returns empty dict on error."""
    try:
        response = s3_client.get_object_tagging(
            Bucket=bucket, Key=key)
        return {
            tag["Key"]: tag["Value"]
            for tag in response.get("TagSet", [])
        }
    except Exception as exc:
        log.warning("Could not fetch S3 tags — using path defaults",
                    bucket=bucket, key=key, error=str(exc))
        return {}