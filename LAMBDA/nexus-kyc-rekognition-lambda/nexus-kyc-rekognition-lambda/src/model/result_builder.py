# src/model/result_builder.py
"""
Builds the complete RekognitionResult payload.

This dict is serialized to JSON and sent to the SQS queue.
The nexus-ai-kyc-service deserializes it and uses:
  - consolidatedText    → injected into GPT-4V system prompt
  - detectedDocumentTypeHint → tells vision model what type to expect
  - textElements        → spatial text for cross-referencing
  - faceDetection       → quality metrics for compliance record
  - qualityAssessment   → whether to proceed or reject user
"""

import os
from datetime import datetime, timezone
from typing import Optional


def build_complete_result(
        processing_id: str,
        metadata: dict,
        text_result: Optional[dict],
        face_result: Optional[dict],
        text_error: Optional[str],
        face_error: Optional[str],
        quality_assessment: dict) -> dict:

    processing_status = (
        "QUALITY_PASSED"
        if quality_assessment["canProceedToAiModel"]
        else "QUALITY_REJECTED"
    )

    return {
        # Identity
        "processingId": processing_id,
        "s3Key": metadata["s3Key"],
        "s3Bucket": metadata["s3Bucket"],
        "userId": metadata["userId"],
        "verificationId": metadata["verificationId"],
        "documentType": metadata["documentType"],
        "attemptNumber": metadata["attemptNumber"],

        # Status
        "processingStatus": processing_status,

        # Quality
        "qualityAssessment": quality_assessment,

        # Rekognition outputs — full detail for AI model context
        "textDetection": text_result,
        "faceDetection": face_result,
        "textDetectionError": text_error,
        "faceDetectionError": face_error,

        # Convenience fields (avoid deep nesting in consumer)
        "detectedDocumentTypeHint": (
            (text_result or {}).get("detectedDocumentTypeHint",
                                    "UNKNOWN")
        ),
        "consolidatedText": (
            (text_result or {}).get("consolidatedText", "")
        ),
        "faceDetected": (
                (face_result or {}).get("faceCount", 0) > 0
        ),
        "detectedTextCount": (
            (text_result or {}).get("lineCount", 0)
        ),
        "faceQualityBrightness": (
            (((face_result or {}).get("primaryFace") or {})
             .get("quality") or {})
            .get("brightness", 0)
        ),
        "faceQualitySharpness": (
            (((face_result or {}).get("primaryFace") or {})
             .get("quality") or {})
            .get("sharpness", 0)
        ),

        # Provenance
        "processedAt": datetime.now(timezone.utc).isoformat(),
        "lambdaVersion": os.environ.get(
            "AWS_LAMBDA_FUNCTION_VERSION", "LATEST"),
        "sourceService": "nexus-kyc-rekognition-lambda",
    }


def build_error_result(processing_id: str,
                       metadata: Optional[dict],
                       key: str,
                       bucket: str,
                       error_type: str,
                       error_message: str) -> dict:
    return {
        "processingId": processing_id,
        "s3Key": key,
        "s3Bucket": bucket,
        "userId": (metadata or {}).get("userId"),
        "verificationId": (metadata or {}).get("verificationId"),
        "processingStatus": "ERROR",
        "errorType": error_type,
        "errorMessage": error_message,
        "processedAt": datetime.now(timezone.utc).isoformat(),
        "sourceService": "nexus-kyc-rekognition-lambda",
    }


def build_rejection_result(processing_id: str,
                           metadata: dict,
                           validation_reasons: list[str]) -> dict:
    return {
        "processingId": processing_id,
        "s3Key": metadata["s3Key"],
        "s3Bucket": metadata["s3Bucket"],
        "userId": metadata["userId"],
        "verificationId": metadata["verificationId"],
        "documentType": metadata.get("documentType", "UNKNOWN"),
        "processingStatus": "PRE_VALIDATION_REJECTED",
        "rejectionReasons": validation_reasons,
        "processedAt": datetime.now(timezone.utc).isoformat(),
        "sourceService": "nexus-kyc-rekognition-lambda",
    }