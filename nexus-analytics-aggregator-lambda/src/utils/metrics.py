# src/utils/metrics.py
"""CloudWatch metric datum builders."""

from datetime import datetime, timezone


def build_metric(name: str, value: float, unit: str,
                 dimensions: list[tuple[str, str]]) -> dict:
    """Build a CloudWatch MetricDatum dict."""
    return {
        "MetricName": name,
        "Value": value,
        "Unit": unit,
        "Timestamp": datetime.now(timezone.utc),
        "Dimensions": [
            {"Name": k, "Value": v} for k, v in dimensions
        ],
    }