# src/alerting/message_builder.py
"""Build structured alert and recovery message payloads."""

import os
from datetime import datetime, timezone
from typing import Optional

ENVIRONMENT = os.environ.get("ENVIRONMENT", "dev")


def build_alert(result: dict, consecutive_failures: int,
                is_new: bool) -> dict:
    """Build a health alert payload for SNS."""
    name = result["name"]
    criticality = result["criticality"]
    status = result["status"]
    downtime_min = (consecutive_failures - 1) * 5

    return {
        "alertType": "NEW_OUTAGE" if is_new else "ONGOING_OUTAGE",
        "serviceName": name,
        "serviceDescription": result.get("description", ""),
        "status": status,
        "statusDetail": result.get("statusDetail", "UNKNOWN"),
        "criticality": criticality,
        "consecutiveFailures": consecutive_failures,
        "estimatedDowntimeMinutes": downtime_min,
        "responseMs": result.get("responseMs"),
        "downComponents": [
            n for n, d in result.get("components", {}).items()
            if d.get("isDown", False)
        ],
        "componentErrors": {
            n: d.get("error", "")
            for n, d in result.get("components", {}).items()
            if d.get("isDown") and d.get("error")
        },
        "blocksTransactions": result.get("blocksTransactions", False),
        "sagaParticipant": result.get("sagaParticipant", False),
        "environment": ENVIRONMENT,
        "detectedAt": datetime.now(timezone.utc).isoformat(),
    }


def build_recovery(result: dict,
                   previous_failures: int) -> dict:
    """Build a recovery notification payload."""
    return {
        "alertType": "RECOVERY",
        "serviceName": result["name"],
        "serviceDescription": result.get("description", ""),
        "status": "UP",
        "criticality": result["criticality"],
        "responseMs": result.get("responseMs"),
        "previousConsecutiveFailures": previous_failures,
        "estimatedDowntimeMinutes": previous_failures * 5,
        "recoveredAt": datetime.now(timezone.utc).isoformat(),
        "environment": ENVIRONMENT,
    }


def build_subject(payload: dict) -> str:
    """Build email subject line (max 100 chars for SNS)."""
    alert_type = payload.get("alertType", "ALERT")
    name = payload.get("serviceName", "unknown")
    status = payload.get("status", "DOWN")
    criticality = payload.get("criticality", "STANDARD")
    env = payload.get("environment", "dev").upper()

    if alert_type == "RECOVERY":
        downtime = payload.get("estimatedDowntimeMinutes", 0)
        subject = (
            f"✅ RECOVERED: {name} is UP "
            f"(~{downtime}min down) — Nexus {env}"
        )
    else:
        prefix = {
            "CRITICAL": "🚨 CRITICAL",
            "HIGH":     "⚠️ HIGH",
            "STANDARD": "⚡ ALERT",
        }.get(criticality, "⚡ ALERT")

        blocks = " [BLOCKS TXN]" \
            if payload.get("blocksTransactions") else ""

        subject = (
            f"{prefix}: {name} is {status}"
            f"{blocks} — Nexus {env}"
        )

    return subject[:100]