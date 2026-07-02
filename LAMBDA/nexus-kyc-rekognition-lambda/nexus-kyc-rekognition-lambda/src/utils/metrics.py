# src/utils/metrics.py
"""
CloudWatch custom metrics for the KYC Rekognition pipeline.

Namespace: Nexus/KycRekognition

Metrics published after every document processed:
  DocumentsProcessed     — count, tagged by QualityTier + DocumentType
  QualityGatePassed      — 1.0 or 0.0 (percentage calculable from avg)
  TextLinesDetected      — gauge: how many text lines Rekognition found
  FaceDetected           — 1.0 or 0.0
  RekognitionCallDuration — histogram: parallel execution time

Alarms watching these metrics (defined in template.yaml):
  HighRejectionRateAlarm  — fires if QualityGatePassed avg < 0.6
  ResultsQueueDepthAlarm  — fires if queue depth > 50
"""

import os
import time
from typing import Optional

import boto3

from utils.logging_config import log

_cw = boto3.client("cloudwatch",
                   region_name=os.environ.get("AWS_REGION_NAME",
                                              "us-east-1"))
_NS = "Nexus/KycRekognition"


def emit_processing_metrics(
        quality_assessment: dict,
        metadata: dict,
        rekognition_duration_ms: int) -> None:
    """Emit all metrics for a processed document."""
    try:
        tier = quality_assessment.get("qualityTier", "UNKNOWN")
        doc_type = metadata.get("documentType", "UNKNOWN")
        passed = quality_assessment.get("passedAllGates", False)

        _cw.put_metric_data(
            Namespace=_NS,
            MetricData=[
                # Count by quality tier × document type
                {
                    "MetricName": "DocumentsProcessed",
                    "Value": 1,
                    "Unit": "Count",
                    "Dimensions": [
                        {"Name": "QualityTier", "Value": tier},
                        {"Name": "DocumentType", "Value": doc_type},
                    ],
                },
                # Binary: 1=passed, 0=failed
                {
                    "MetricName": "QualityGatePassed",
                    "Value": 1.0 if passed else 0.0,
                    "Unit": "None",
                },
                # Rekognition parallel call duration
                {
                    "MetricName": "RekognitionCallDuration",
                    "Value": rekognition_duration_ms,
                    "Unit": "Milliseconds",
                },
            ],
        )

        # Separate metric per failed gate reason
        for reason in quality_assessment.get("failedGates", []):
            _cw.put_metric_data(
                Namespace=_NS,
                MetricData=[{
                    "MetricName": "GateFailureReasons",
                    "Value": 1,
                    "Unit": "Count",
                    "Dimensions": [
                        {"Name": "Reason", "Value": reason},
                    ],
                }],
            )

    except Exception as exc:
        # Metrics failure must never fail the processing pipeline
        log.warning("CloudWatch metrics publish failed",
                    error=str(exc))