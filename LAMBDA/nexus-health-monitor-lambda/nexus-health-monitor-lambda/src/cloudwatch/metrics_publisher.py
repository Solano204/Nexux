# src/cloudwatch/metrics_publisher.py
"""
Publish health check results as CloudWatch metrics.
Batched in groups of 20 (CloudWatch API limit per PutMetricData call).

Metrics:
  ServiceHealthStatus     → 1.0=UP, 0.0=not-UP per service
  ServiceHealthCheckDuration → response time in ms per service
  ConsecutiveFailures     → failure streak per service (for state)
  ComponentHealthStatus   → per component (db, kafka, redis...)
  HealthyServiceCount     → platform-wide summary
  UnhealthyServiceCount   → platform-wide summary
"""

import os
from datetime import datetime, timezone

import boto3

from utils.logging_config import log

_cw = boto3.client("cloudwatch",
                   region_name=os.environ.get(
                       "AWS_REGION_NAME", "us-east-1"))

CW_NAMESPACE = os.environ.get("CLOUDWATCH_NAMESPACE",
                              "Nexus/HealthMonitor")
ENVIRONMENT = os.environ.get("ENVIRONMENT", "dev")


def publish_all(results: list[dict],
                timestamp: datetime) -> None:
    """Publish all health metrics for this check cycle."""
    metrics: list[dict] = []

    healthy = 0
    unhealthy = 0

    for r in results:
        name = r["name"]
        status = r["status"]
        response_ms = r.get("responseMs")
        criticality = r["criticality"]
        is_up = status == "UP"

        if is_up:
            healthy += 1
        else:
            unhealthy += 1

        # Service health status: 1.0=UP, 0.0=not-UP
        metrics.append(_datum(
            "ServiceHealthStatus",
            1.0 if is_up else 0.0,
            "None",
            [("ServiceName", name),
             ("Criticality", criticality),
             ("Environment", ENVIRONMENT)],
            timestamp,
        ))

        # Response time (only when we got a response)
        if response_ms is not None:
            metrics.append(_datum(
                "ServiceHealthCheckDuration",
                float(response_ms),
                "Milliseconds",
                [("ServiceName", name),
                 ("Environment", ENVIRONMENT)],
                timestamp,
            ))

        # Per-component status for DOWN components
        for comp_name, comp_data in r.get("components", {}).items():
            if comp_data.get("isDown", False):
                metrics.append(_datum(
                    "ComponentHealthStatus",
                    0.0, "None",
                    [("ServiceName", name),
                     ("ComponentName", comp_name),
                     ("Environment", ENVIRONMENT)],
                    timestamp,
                ))

    # Platform-wide summary
    metrics.append(_datum(
        "HealthyServiceCount", float(healthy), "Count",
        [("Environment", ENVIRONMENT)], timestamp))
    metrics.append(_datum(
        "UnhealthyServiceCount", float(unhealthy), "Count",
        [("Environment", ENVIRONMENT)], timestamp))

    # Batch publish (20 per call)
    _batch_publish(metrics)


def record_failure_count(service_name: str, count: int,
                         timestamp: datetime) -> None:
    _batch_publish([_datum(
        "ConsecutiveFailures", float(count), "Count",
        [("ServiceName", service_name),
         ("Environment", ENVIRONMENT)],
        timestamp)])


def reset_failure_count(service_name: str,
                        timestamp: datetime) -> None:
    record_failure_count(service_name, 0, timestamp)


def _batch_publish(metrics: list[dict]) -> None:
    for i in range(0, len(metrics), 20):
        chunk = metrics[i:i + 20]
        try:
            _cw.put_metric_data(
                Namespace=CW_NAMESPACE, MetricData=chunk)
        except Exception as exc:
            log.warning("CloudWatch metrics batch failed "
                        "(non-fatal)",
                        batch_start=i, count=len(chunk),
                        error=str(exc))


def _datum(name: str, value: float, unit: str,
           dims: list[tuple[str, str]],
           timestamp: datetime) -> dict:
    return {
        "MetricName": name,
        "Value": value,
        "Unit": unit,
        "Timestamp": timestamp,
        "Dimensions": [
            {"Name": k, "Value": v} for k, v in dims
        ],
    }