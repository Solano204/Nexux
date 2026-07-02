"""
Platform-wide failure scenario detection.

Beyond individual service alerts, some failure combinations
require special escalation because they mean no transactions
can be processed — even if individual service alerts are firing.

Scenarios:
1. ALL_TRANSACTION_SERVICES_DOWN:
   All services in TRANSACTION_BLOCKING_SERVICES are down.
   No transactions possible. CRITICAL alert.

2. INFRASTRUCTURE_SERVICES_DOWN:
   Config or Discovery service down.
   Service restarts + new deployments will fail.
   STANDARD alert (current services continue on cached config).

3. CASCADE_FAILURE:
   >= 50% of services and >= 5 services are down simultaneously.
   Indicates host-level infrastructure problem, not service bugs.
   CRITICAL alert.

4. SAGA_PARTICIPANTS_DOWN (warning only):
   In-flight SAGAs may be stuck waiting for responses.
   Logged prominently but not a separate alert (individual service
   alerts already fire for each down participant).
"""

from registry.service_registry import TRANSACTION_BLOCKING_SERVICES
from utils.logging_config import log


def analyze(results: list[dict]) -> list[dict]:
    """
    Analyze the complete set of health results for platform scenarios.
    Returns list of scenario alert dicts (may be empty).
    """
    scenarios = []
    by_name = {r["name"]: r for r in results}

    scenarios.extend(
        _check_all_tx_services_down(results, by_name))
    scenarios.extend(
        _check_infrastructure_down(results))
    scenarios.extend(
        _check_cascade_failure(results))
    _log_saga_participants_down(results)

    return scenarios


def _check_all_tx_services_down(results: list[dict],
                                  by_name: dict) -> list[dict]:
    statuses = {
        svc: by_name.get(svc, {}).get("status", "UNKNOWN")
        for svc in TRANSACTION_BLOCKING_SERVICES
        if svc in by_name  # Only check services present in results
    }

    if not statuses:
        return []

    all_down = all(s != "UP" for s in statuses.values())

    if not all_down:
        return []

    return [{
        "scenarioType": "ALL_TRANSACTION_SERVICES_DOWN",
        "severity": "CRITICAL",
        "description": (
            "ALL services required for financial transaction "
            "processing are currently DOWN or DEGRADED. "
            "No transactions can be initiated or completed."
        ),
        "affectedServices": list(TRANSACTION_BLOCKING_SERVICES),
        "serviceStatuses": statuses,
        "blocksTransactions": True,
        "immediateAction": (
            "Investigate host-level infrastructure. "
            "Check Docker daemon, disk space, memory."
        ),
    }]


def _check_infrastructure_down(
        results: list[dict]) -> list[dict]:
    down_infra = [
        r["name"] for r in results
        if r["criticality"] == "INFRASTRUCTURE"
        and r["status"] != "UP"
    ]
    if not down_infra:
        return []

    return [{
        "scenarioType": "INFRASTRUCTURE_SERVICES_DOWN",
        "severity": "HIGH",
        "description": (
            f"Infrastructure services down: {', '.join(down_infra)}. "
            "Service restarts and new deployments will fail. "
            "Currently running services continue operating "
            "with cached configuration."
        ),
        "affectedServices": down_infra,
        "blocksTransactions": False,
    }]


def _check_cascade_failure(results: list[dict]) -> list[dict]:
    total = len(results)
    down = [r for r in results if r["status"] != "UP"]
    down_count = len(down)

    if total == 0:
        return []

    down_pct = (down_count / total) * 100

    if down_pct < 50 or down_count < 5:
        return []

    return [{
        "scenarioType": "CASCADE_FAILURE",
        "severity": "CRITICAL",
        "description": (
            f"{down_count} of {total} services ({down_pct:.0f}%) "
            "are DOWN or DEGRADED simultaneously. "
            "This indicates a platform-wide infrastructure failure, "
            "not individual service bugs."
        ),
        "downCount": down_count,
        "totalCount": total,
        "downPercent": round(down_pct, 1),
        "downServices": [
            {"name": r["name"], "status": r["status"]}
            for r in down
        ],
        "blocksTransactions": True,
        "immediateAction": (
            "Check Docker host: memory, disk, network, CPU. "
            "Check if Docker daemon is healthy."
        ),
    }]


def _log_saga_participants_down(results: list[dict]) -> None:
    saga_down = [
        r["name"] for r in results
        if r.get("sagaParticipant") and r["status"] != "UP"
    ]
    if saga_down:
        log.warning(
            "SAGA participant services are DOWN — "
            "in-flight SAGAs may be stuck",
            services=saga_down,
            note="Saga orchestrator timeout monitor will handle recovery",
        )
