# src/publishing/sqs_publisher.py
"""
Publishes RekognitionResult to the nexus-kyc-rekognition-results SQS queue.

Message attributes allow the nexus-ai-kyc-service to filter
without deserializing the full body:
  verificationId  → route to correct KYC verification record
  userId          → associate with user
  processingStatus → skip QUALITY_REJECTED before expensive AI call
  qualityTier      → ACCEPTABLE | MARGINAL | REJECTED

SQS message body: full JSON result.
Max SQS message size is 256 KB; our results are ~10-30 KB.
"""

import json
import os

import boto3

from utils.logging_config import log

sqs_client = boto3.client("sqs")
QUEUE_URL = os.environ["RESULTS_QUEUE_URL"]


def publish_result(result: dict) -> str:
    """
    Publish the result dict to SQS.
    Returns the SQS MessageId.
    Raises on SQS error (caller handles).
    """
    body = json.dumps(result, ensure_ascii=False, default=str)

    response = sqs_client.send_message(
        QueueUrl=QUEUE_URL,
        MessageBody=body,
        MessageAttributes={
            "verificationId": _str_attr(
                result.get("verificationId", "unknown")),
            "userId": _str_attr(
                result.get("userId", "unknown")),
            "processingStatus": _str_attr(
                result.get("processingStatus", "UNKNOWN")),
            "qualityTier": _str_attr(
                result.get("qualityAssessment", {})
                .get("qualityTier", "UNKNOWN")),
        },
    )

    message_id = response["MessageId"]
    log.info("SQS publish successful",
             message_id=message_id,
             verification_id=result.get("verificationId"),
             status=result.get("processingStatus"))

    return message_id


def _str_attr(value: str) -> dict:
    return {"DataType": "String", "StringValue": str(value)}