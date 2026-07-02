# src/service/dashboard_manager.py
"""
Service-level CloudWatch dashboard manager.

While cloudwatch/dashboard_manager.py maintains the platform-wide
overview dashboard (all 15 services as single-value indicators),
this module creates per-service detail dashboards showing:
  - Health status time series (UP/DOWN over 24h)
  - Response time P50/P99
  - Component health breakdown
  - Consecutive failure history

Dashboards are created on-demand when a service transitions
to an unhealthy state, providing deep-dive visibility for
the specific service under investigation.
"""

import json
import os
from typing import Optional

import boto3

from utils.logging_config import log

_cw = boto3.client(
    "cloudwatch",
    region_name=os.environ.get("AWS_REGION_NAME", "us-east-1"),
)

CW_NAMESPACE = os.environ.get("CLOUDWATCH_NAMESPACE", "Nexus/HealthMonitor")
ENVIRONMENT = os.environ.get("ENVIRONMENT", "dev")


def ensure_service_dashboard(service_name: str,
                             criticality: str,
                             components: Optional[list[str]] = None) -> None:
    """
    Create or update a per-service detail CloudWatch dashboard.
    Called when a service enters an unhealthy state for deeper visibility.
    """
    dashboard_name = f"nexus-service-{service_name}-{ENVIRONMENT}"
    short_name = service_name.replace("nexus-", "")

    widgets = []
    y = 0

    # Row 1: Health status time series (24h)
    widgets.append({
        "type": "metric",
        "x": 0, "y": y, "width": 12, "height": 6,
        "properties": {
            "title": f"{short_name} — Health Status (24h)",
            "metrics": [[
                CW_NAMESPACE, "ServiceHealthStatus",
                "ServiceName", service_name,
                "Environment", ENVIRONMENT,
                {"stat": "Minimum", "period": 300, "color": "#2ca02c"},
            ]],
            "view": "timeSeries",
            "period": 300,
            "yAxis": {"left": {"min": 0, "max": 1}},
        },
    })

    # Row 1 right: Response time P50/P99
    widgets.append({
        "type": "metric",
        "x": 12, "y": y, "width": 12, "height": 6,
        "properties": {
            "title": f"{short_name} — Response Time (ms)",
            "metrics": [
                [CW_NAMESPACE, "ServiceHealthCheckDuration",
                 "ServiceName", service_name,
                 "Environment", ENVIRONMENT,
                 {"stat": "p50", "label": "P50", "color": "#1f77b4"}],
                [CW_NAMESPACE, "ServiceHealthCheckDuration",
                 "ServiceName", service_name,
                 "Environment", ENVIRONMENT,
                 {"stat": "p99", "label": "P99", "color": "#d62728"}],
            ],
            "view": "timeSeries",
            "period": 300,
        },
    })
    y += 6

    # Row 2: Consecutive failures
    widgets.append({
        "type": "metric",
        "x": 0, "y": y, "width": 12, "height": 4,
        "properties": {
            "title": f"{short_name} — Consecutive Failures",
            "metrics": [[
                CW_NAMESPACE, "ConsecutiveFailures",
                "ServiceName", service_name,
                "Environment", ENVIRONMENT,
                {"stat": "Maximum", "period": 300, "color": "#ff7f0e"},
            ]],
            "view": "timeSeries",
            "period": 300,
            "yAxis": {"left": {"min": 0}},
        },
    })

    # Row 2 right: Component health breakdown
    if components:
        comp_metrics = []
        colors = ["#2ca02c", "#d62728", "#ff7f0e", "#1f77b4", "#9467bd"]
        for i, comp in enumerate(components):
            comp_metrics.append([
                CW_NAMESPACE, "ComponentHealthStatus",
                "ServiceName", service_name,
                "ComponentName", comp,
                "Environment", ENVIRONMENT,
                {"stat": "Minimum", "period": 300,
                 "label": comp,
                 "color": colors[i % len(colors)]},
            ])

        widgets.append({
            "type": "metric",
            "x": 12, "y": y, "width": 12, "height": 4,
            "properties": {
                "title": f"{short_name} — Component Health",
                "metrics": comp_metrics,
                "view": "timeSeries",
                "period": 300,
                "yAxis": {"left": {"min": 0, "max": 1}},
            },
        })
    y += 4

    # Row 3: Alarm status
    widgets.append({
        "type": "alarm",
        "x": 0, "y": y, "width": 24, "height": 3,
        "properties": {
            "title": f"{short_name} — Alarm Status",
            "alarms": [
                f"arn:aws:cloudwatch:{os.environ.get('AWS_REGION_NAME', 'us-east-1')}:"
                f"nexus-service-health-{service_name}-{ENVIRONMENT}"
            ],
        },
    })

    try:
        _cw.put_dashboard(
            DashboardName=dashboard_name,
            DashboardBody=json.dumps({"widgets": widgets}),
        )
        log.info("Service dashboard updated",
                 dashboard=dashboard_name, service=service_name)
    except Exception as exc:
        log.warning("Service dashboard update failed",
                    dashboard=dashboard_name, error=str(exc))


def delete_service_dashboard(service_name: str) -> None:
    """Remove a service detail dashboard (e.g. after extended healthy period)."""
    dashboard_name = f"nexus-service-{service_name}-{ENVIRONMENT}"
    try:
        _cw.delete_dashboards(DashboardNames=[dashboard_name])
        log.info("Service dashboard deleted", dashboard=dashboard_name)
    except Exception as exc:
        log.warning("Service dashboard delete failed",
                    dashboard=dashboard_name, error=str(exc))
