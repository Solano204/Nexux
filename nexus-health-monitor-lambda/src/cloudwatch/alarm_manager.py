# src/cloudwatch/alarm_manager.py
"""
Programmatic CloudWatch Alarm management.

One alarm per service: nexus-service-health-{name}-{env}

Alarms are created on first Lambda invocation and updated on each
subsequent run. This means adding a service to the registry
automatically creates its CloudWatch Alarm without any manual
AWS Console work.

Alarm design:
  Metric: ServiceHealthStatus Minimum over 5-minute period
  Threshold: < 1.0 (anything other than UP)
  EvaluationPeriods: depends on criticality
  TreatMissingData: breaching (missing = down)
  AlarmActions + OKActions: SNS topics (both alert and recovery)
"""

import os
import boto3
from utils.logging_config import log

_cw = boto3.client("cloudwatch",
                   region_name=os.environ.get(
                       "AWS_REGION_NAME", "us-east-1"))

CW_NAMESPACE = os.environ.get("CLOUDWATCH_NAMESPACE",
                              "Nexus/HealthMonitor")
ENVIRONMENT = os.environ.get("ENVIRONMENT", "dev")
CRITICAL_TOPIC = os.environ.get("CRITICAL_ALERT_TOPIC_ARN", "")
STANDARD_TOPIC = os.environ.get("STANDARD_ALERT_TOPIC_ARN", "")

# Evaluation periods (5-min each) before alarm fires
_EVAL_PERIODS = {
    "CRITICAL":       1,   # 5 min
    "HIGH":           2,   # 10 min
    "STANDARD":       2,   # 10 min
    "INFRASTRUCTURE": 3,   # 15 min
}


def update_alarm(service_name: str, criticality: str,
                 current_status: str) -> None:
    """Create or update the CloudWatch alarm for a service."""
    alarm_name = (f"nexus-service-health-"
                  f"{service_name}-{ENVIRONMENT}")

    # Alarm actions: CRITICAL gets paged, everything gets standard
    actions = [STANDARD_TOPIC]
    if criticality == "CRITICAL" and CRITICAL_TOPIC:
        actions.insert(0, CRITICAL_TOPIC)

    try:
        _cw.put_metric_alarm(
            AlarmName=alarm_name,
            AlarmDescription=(
                f"Health status for {service_name} "
                f"({criticality}). "
                f"Triggers on DOWN/DEGRADED."
            ),
            MetricName="ServiceHealthStatus",
            Namespace=CW_NAMESPACE,
            Statistic="Minimum",
            Dimensions=[
                {"Name": "ServiceName", "Value": service_name},
                {"Name": "Environment", "Value": ENVIRONMENT},
            ],
            Period=300,
            EvaluationPeriods=_EVAL_PERIODS.get(criticality, 2),
            Threshold=1.0,
            ComparisonOperator="LessThanThreshold",
            TreatMissingData="breaching",
            # OKActions: also notify when service recovers
            AlarmActions=actions,
            OKActions=actions,
            InsufficientDataActions=actions,
        )
        log.debug("CloudWatch alarm updated",
                  alarm=alarm_name, status=current_status)
    except Exception as exc:
        log.error("Failed to update CloudWatch alarm",
                  alarm=alarm_name, error=str(exc))


def set_alarm_ok(service_name: str) -> None:
    """Force alarm to OK state after recovery detection."""
    alarm_name = (f"nexus-service-health-"
                  f"{service_name}-{ENVIRONMENT}")
    try:
        _cw.set_alarm_state(
            AlarmName=alarm_name,
            StateValue="OK",
            StateReason=f"{service_name} recovered — health check passed",
        )
    except Exception as exc:
        log.warning("Failed to set alarm to OK",
                    alarm=alarm_name, error=str(exc))