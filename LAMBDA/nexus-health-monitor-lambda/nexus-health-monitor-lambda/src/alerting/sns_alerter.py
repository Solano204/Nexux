# src/alerting/sns_alerter.py
"""Publish health alerts and recovery notifications to SNS."""

import json
import os

import boto3

from src.utils.logging_config import log
from src.alerting.message_builder import build_subject

_sns = boto3.client("sns",
                    region_name=os.environ.get(
                        "AWS_REGION_NAME", "us-east-1"))

CRITICAL_TOPIC = os.environ.get("CRITICAL_ALERT_TOPIC_ARN", "")
STANDARD_TOPIC = os.environ.get("STANDARD_ALERT_TOPIC_ARN", "")
ENVIRONMENT = os.environ.get("ENVIRONMENT", "dev")


def send_alert(payload: dict, topic_type: str) -> bool:
    """
    Publish an alert to the appropriate SNS topic.
    Returns True on success, False on failure (non-fatal).
    """
    topic_arn = (CRITICAL_TOPIC
                 if topic_type == "critical"
                 else STANDARD_TOPIC)

    if not topic_arn:
        log.warning("SNS topic ARN not configured",
                    topic_type=topic_type)
        return False

    subject = build_subject(payload)

    try:
        _sns.publish(
            TopicArn=topic_arn,
            Subject=subject,
            Message=json.dumps(payload, indent=2, default=str),
            MessageAttributes={
                "alertType": _attr(
                    payload.get("alertType", "ALERT")),
                "serviceName": _attr(
                    payload.get("serviceName", "unknown")),
                "criticality": _attr(
                    payload.get("criticality", "STANDARD")),
                "status": _attr(
                    payload.get("status", "DOWN")),
                "blocksTransactions": _attr(
                    str(payload.get("blocksTransactions", False))),
                "environment": _attr(ENVIRONMENT),
            },
        )
        log.info("SNS alert published",
                 subject=subject, topic=topic_type,
                 service=payload.get("serviceName"))
        return True

    except Exception as exc:
        log.error("SNS publish failed (non-fatal)",
                  service=payload.get("serviceName"),
                  topic=topic_type, error=str(exc))
        return False


def _attr(value: str) -> dict:
    return {"DataType": "String", "StringValue": value}