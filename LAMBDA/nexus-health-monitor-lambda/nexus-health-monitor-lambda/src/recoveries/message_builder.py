# src/recoveries/message_builder.py
"""
Recovery-specific message builder.

Constructs detailed recovery notification payloads with
downtime calculation, affected component restoration details,
and SAGA impact assessment for services that were down.

Used by the handler's recovery detection flow after a service
transitions from DOWN/DEGRADED back to UP. Complements the
alerting.message_builder.build_recovery() with richer context
for post-incident reporting.
"""

import os
from datetime import datetime, timezone
from typing import Optional

ENVIRONMENT = os.environ.get("ENVIRONMENT", "dev")
CHECK_INTERVAL_MIN = 5  # health check runs every 5 minutes


def build_recovery_message(result: dict,
                           previous_failures: int,
                           previous_status: Optional[str] = None) -> dict:
    """
    Build a detailed recovery notification payload.

    Args:
        result: Current health check result (status=UP).
        previous_failures: Number of consecutive failures before recovery.
        previous_status: Last known bad status (DOWN, DEGRADED, UNKNOWN).

    Returns:
        Structured recovery payload for SNS publication.
    """
    name = result["name"]
    criticality = result["criticality"]
    downtime_min = previous_failures * CHECK_INTERVAL_MIN

    return {
        "alertType": "RECOVERY",
        "serviceName": name,
        "serviceDescription": result.get("description", ""),
        "status": "UP",
        "previousStatus": previous_status or "DOWN",
        "criticality": criticality,
        "responseMs": result.get("responseMs"),
        "previousConsecutiveFailures": previous_failures,
        "estimatedDowntimeMinutes": downtime_min,
        "recoveredComponents": _recovered_components(result),
        "sagaImpact": _saga_impact(result, downtime_min),
        "transactionImpact": _transaction_impact(result, downtime_min),
        "recoveredAt": datetime.now(timezone.utc).isoformat(),
        "environment": ENVIRONMENT,
    }


def build_recovery_subject(name: str, downtime_min: int,
                           criticality: str) -> str:
    """Build SNS subject line for recovery notification."""
    env = ENVIRONMENT.upper()
    subject = (
        f"✅ RECOVERED: {name} is UP "
        f"(~{downtime_min}min down) — Nexus {env}"
    )
    return subject[:100]


def build_bulk_recovery_message(recoveries: list[dict]) -> dict:
    """
    Build a summary message when multiple services recover simultaneously.
    Indicates infrastructure-level recovery (e.g. Docker restart).
    """
    return {
        "alertType": "BULK_RECOVERY",
        "recoveredCount": len(recoveries),
        "services": [
            {
                "name": r.get("serviceName", ""),
                "downtime": r.get("estimatedDowntimeMinutes", 0),
                "criticality": r.get("criticality", "STANDARD"),
            }
            for r in recoveries
        ],
        "totalEstimatedDowntime": max(
            (r.get("estimatedDowntimeMinutes", 0) for r in recoveries),
            default=0,
        ),
        "likelyInfraRecovery": len(recoveries) >= 3,
        "recoveredAt": datetime.now(timezone.utc).isoformat(),
        "environment": ENVIRONMENT,
    }


def _recovered_components(result: dict) -> list[str]:
    """List components that are now healthy."""
    return [
        name for name, data in result.get("components", {}).items()
        if not data.get("isDown", False)
    ]


def _saga_impact(result: dict, downtime_min: int) -> Optional[str]:
    """Assess SAGA impact if this service participates in SAGAs."""
    if not result.get("sagaParticipant", False):
        return None
    if downtime_min <= 5:
        return "Minimal — single check interval, SAGA timeout likely handled recovery."
    return (
        f"SAGAs involving {result['name']} may have timed out during "
        f"~{downtime_min}min outage. Check saga-orchestrator for stuck SAGAs."
    )


def _transaction_impact(result: dict, downtime_min: int) -> Optional[str]:
    """Assess transaction processing impact."""
    if not result.get("blocksTransactions", False):
        return None
    return (
        f"Transaction processing was blocked for ~{downtime_min} minutes. "
        f"Queued transactions should resume automatically."
    )
