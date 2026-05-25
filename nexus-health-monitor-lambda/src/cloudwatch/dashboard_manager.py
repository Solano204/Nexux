# src/cloudwatch/dashboard_manager.py
"""
Maintain the platform health CloudWatch dashboard.

Called on every Lambda invocation — idempotent (put_dashboard
is a create-or-replace operation). Adding a service to the registry
automatically adds it to the dashboard on the next health check run.

Dashboard layout:
  Row 1+: Single-value health indicators, 6 per row
  Final row: Time-series showing healthy vs unhealthy count over time
"""

import json
import os

import boto3

from utils.logging_config import log

_cw = boto3.client("cloudwatch",
                   region_name=os.environ.get(
                       "AWS_REGION_NAME", "us-east-1"))

CW_NAMESPACE = os.environ.get("CLOUDWATCH_NAMESPACE",
                              "Nexus/HealthMonitor")
ENVIRONMENT = os.environ.get("ENVIRONMENT", "dev")


def ensure_dashboard(services: list[dict]) -> None:
    widgets: list[dict] = []
    y = 0

    # Per-service health indicators (6 per row)
    for i in range(0, len(services), 6):
        batch = services[i:i + 6]
        for j, svc in enumerate(batch):
            name = svc["name"]
            short = name.replace("nexus-", "")
            color = {
                "CRITICAL":       "#d62728",
                "HIGH":           "#ff7f0e",
                "STANDARD":       "#2ca02c",
                "INFRASTRUCTURE": "#9467bd",
            }.get(svc["criticality"], "#7f7f7f")

            widgets.append({
                "type": "metric",
                "x": j * 4, "y": y,
                "width": 4, "height": 3,
                "properties": {
                    "title": short,
                    "metrics": [[
                        CW_NAMESPACE, "ServiceHealthStatus",
                        "ServiceName", name,
                        "Environment", ENVIRONMENT,
                        {"stat": "Minimum", "period": 300,
                         "color": color},
                    ]],
                    "view": "singleValue",
                    "period": 300,
                    "yAxis": {"left": {"min": 0, "max": 1}},
                },
            })
        y += 3

    # Time-series: healthy vs unhealthy over last 3 hours
    widgets.append({
        "type": "metric",
        "x": 0, "y": y,
        "width": 24, "height": 6,
        "properties": {
            "title": "Platform Health Overview",
            "metrics": [
                [CW_NAMESPACE, "HealthyServiceCount",
                 "Environment", ENVIRONMENT,
                 {"stat": "Minimum", "label": "Healthy",
                  "color": "#2ca02c"}],
                [CW_NAMESPACE, "UnhealthyServiceCount",
                 "Environment", ENVIRONMENT,
                 {"stat": "Maximum", "label": "Unhealthy",
                  "color": "#d62728"}],
            ],
            "view": "timeSeries",
            "period": 300,
            "yAxis": {"left": {"min": 0, "max": len(services)}},
        },
    })

    try:
        _cw.put_dashboard(
            DashboardName=f"nexus-platform-health-{ENVIRONMENT}",
            DashboardBody=json.dumps({"widgets": widgets}),
        )
    except Exception as exc:
        log.warning("Dashboard update failed", error=str(exc))