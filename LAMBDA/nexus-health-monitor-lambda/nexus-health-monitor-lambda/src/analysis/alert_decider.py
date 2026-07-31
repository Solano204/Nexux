"""
Determines whether to send an alert based on consecutive failure count.

State persistence strategy: use CloudWatch as the state store.
  No DynamoDB, no Redis — CloudWatch already has the metric history.
  get_consecutive_failures() reads last N ServiceHealthStatus datapoints.
  Consecutive 0.0 values from the end = current failure streak.

This is elegant but has a limitation: CloudWatch metrics have ~1-2 minute
publication delay, so the very first failure in a burst may read stale
"healthy" history. That's acceptable — we might miss the first-failure
alert, but the second failure (10 min later) will trigger correctly.
For CRITICAL services (alert on first failure), we accept this limitation:
in practice, CRITICAL service alerts should also come through the
service's own internal alerting before the health monitor even runs.
"""

import os
from datetime import datetime, timezone, timedelta

import boto3

from src.utils.logging_config import log

_cw = boto3.client("cloudwatch",
                    region_name=os.environ.get(
                        "AWS_REGION_NAME", "us-east-1"))

CW_NAMESPACE = os.environ.get("CLOUDWATCH_NAMESPACE",
                               "Nexus/HealthMonitor")
ENVIRONMENT = os.environ.get("ENVIRONMENT", "dev")
DEFAULT_THRESHOLD = int(
    os.environ.get("CONSECUTIVE_FAILURES_THRESHOLD", "2"))


def should_alert(result: dict,
                  consecutive_failures: int) -> bool:
    """
    Returns True if an alert should be sent for this failure.
    Threshold varies by criticality tier.
    """
    criticality = result["criticality"]
    threshold = _threshold(criticality)
    return consecutive_failures >= threshold


def should_escalate(consecutive_failures: int) -> bool:
    """True if this is an ongoing outage (not the first alert)."""
    return consecutive_failures > _threshold("STANDARD")


def get_consecutive_failures(service_name: str) -> int:
    """
    Count consecutive failures by reading CloudWatch metric history.
    Returns 0 if no history or all healthy.
    """
    end = datetime.now(timezone.utc)
    start = end - timedelta(hours=1)

    try:
        response = _cw.get_metric_statistics(
            Namespace=CW_NAMESPACE,
            MetricName="ServiceHealthStatus",
            Dimensions=[
                {"Name": "ServiceName", "Value": service_name},
                {"Name": "Environment", "Value": ENVIRONMENT},
            ],
            StartTime=start,
            EndTime=end,
            Period=300,         # 5-minute periods
            Statistics=["Minimum"],
        )

        # Sort oldest → newest
        datapoints = sorted(
            response.get("Datapoints", []),
            key=lambda d: d["Timestamp"])

        if not datapoints:
            return 0

        # Count consecutive failures from the end (most recent)
        consecutive = 0
        for dp in reversed(datapoints):
            if dp["Minimum"] == 0.0:   # 0.0 = DOWN/DEGRADED
                consecutive += 1
            else:
                break  # UP breaks the streak

        return consecutive

    except Exception as exc:
        log.warning("Failed to read failure history from CloudWatch",
                    service=service_name, error=str(exc))
        return 0


def _threshold(criticality: str) -> int:
    thresholds = {
        "CRITICAL":       1,
        "HIGH":           2,
        "STANDARD":       DEFAULT_THRESHOLD,
        "INFRASTRUCTURE": 3,
    }
    return thresholds.get(criticality, DEFAULT_THRESHOLD)
