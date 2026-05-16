# src/checks/health_checker.py
"""
HTTP health check execution with urllib3 connection pooling.

urllib3 PoolManager:
  num_pools=20: one pool per service (even though we have 15)
  maxsize=1: one connection per pool (sequential use per service)
  timeout: connect=3s, read=configurable (default 5s)
  retries=False: report failures immediately, no silent retries

retries=False is the critical decision:
  If a service times out once, that IS the health check result.
  Silent retries would mask latency degradation and delay alerting.
  The health monitor runs every 5 minutes — a retry here would
  mean the next check doesn't happen for another 5 minutes anyway.

Parallel execution via ThreadPoolExecutor (8 workers):
  15 services / 8 workers = 2 batches.
  Wall time ≈ max(slowest service) × 2 batches ≈ 10-15s
  vs sequential worst-case: 15 × 5s = 75s
"""

import json
import os
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from typing import Optional

import urllib3

from utils.logging_config import log
from checks.response_parser import parse_health_response

BRIDGE_SECRET = os.environ.get("PLANE_BRIDGE_SECRET", "")
BASE_URL = os.environ.get("LOCAL_PLANE_BASE_URL",
                          "http://host.docker.internal")
TIMEOUT_SECONDS = float(
    os.environ.get("HEALTH_CHECK_TIMEOUT_SECONDS", "5"))
MAX_WORKERS = int(os.environ.get("MAX_WORKERS", "8"))

# Module-level pool manager: reused across warm Lambda invocations
_http = urllib3.PoolManager(
    num_pools=20,
    maxsize=1,
    timeout=urllib3.Timeout(connect=3.0, read=TIMEOUT_SECONDS),
    retries=False,   # Fail fast — no silent retries
)


def run_checks_parallel(services: list[dict]) -> list[dict]:
    """
    Execute all health checks in parallel via ThreadPoolExecutor.
    All tasks are I/O-bound (HTTP calls) — GIL released during I/O,
    so parallelism is genuine despite the Python GIL.
    """
    results: list[dict] = []

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
        future_to_service = {
            pool.submit(_check_one, svc): svc
            for svc in services
        }

        for future in as_completed(future_to_service,
                                   timeout=90):
            svc = future_to_service[future]
            try:
                results.append(future.result(timeout=15))
            except Exception as exc:
                # The future itself failed, not the HTTP call
                results.append(_error_result(svc,
                                             "FUTURE_EXECUTION_ERROR", str(exc)))

    return results


def run_checks_sequential(services: list[dict]) -> list[dict]:
    """Sequential fallback — used when PARALLEL_CHECKS=false."""
    return [_check_one(svc) for svc in services]


def _check_one(service: dict) -> dict:
    """Execute a single service health check."""
    name = service["name"]
    port = service["port"]
    path = service["health_path"]
    url = f"{BASE_URL}:{port}{path}"

    start_ms = time.monotonic() * 1000

    try:
        response = _http.request(
            "GET", url,
            headers={
                "X-Plane-Bridge-Secret": BRIDGE_SECRET,
                "X-Health-Monitor": "nexus-health-monitor-lambda",
                "X-Check-Timestamp":
                    datetime.now(timezone.utc).isoformat(),
            },
        )

        response_ms = int(time.monotonic() * 1000 - start_ms)
        body = response.data.decode("utf-8", errors="replace")

        if response.status in (200, 503):
            # Both 200 (healthy) and 503 (unhealthy) carry Actuator JSON
            try:
                health_data = json.loads(body)
                return parse_health_response(
                    service, health_data, response_ms)
            except json.JSONDecodeError:
                status = "UP" if response.status == 200 else "DOWN"
                return _build_result(service, status,
                                     "UNPARSEABLE_RESPONSE", response_ms, {})
        else:
            return _build_result(service, "DOWN",
                                 f"HTTP_{response.status}", response_ms, {})

    except urllib3.exceptions.ConnectTimeoutError:
        return _error_result(service, "CONNECTION_TIMEOUT",
                             None, int(time.monotonic() * 1000 - start_ms))

    except urllib3.exceptions.ReadTimeoutError:
        return _error_result(service, "READ_TIMEOUT",
                             None, int(time.monotonic() * 1000 - start_ms))

    except urllib3.exceptions.MaxRetryError:
        return _error_result(service, "CONNECTION_REFUSED",
                             None, int(time.monotonic() * 1000 - start_ms))

    except Exception as exc:
        log.error("Unexpected health check error",
                  service=name, error=str(exc),
                  error_type=type(exc).__name__)
        return _error_result(service,
                             f"UNEXPECTED_ERROR:{type(exc).__name__}",
                             str(exc),
                             int(time.monotonic() * 1000 - start_ms))


def _error_result(service: dict, status_detail: str,
                  error: Optional[str],
                  response_ms: int = 0) -> dict:
    return _build_result(service, "DOWN", status_detail,
                         response_ms, {}, error=error)


def _build_result(service: dict, status: str,
                  status_detail: str, response_ms: int,
                  components: dict,
                  error: Optional[str] = None) -> dict:
    result = {
        "name": service["name"],
        "port": service["port"],
        "status": status,
        "statusDetail": status_detail,
        "responseMs": response_ms,
        "criticality": service["criticality"],
        "description": service["description"],
        "blocksTransactions": service.get("blocks_transactions", False),
        "sagaParticipant": service.get("saga_participant", False),
        "components": components,
        "checkedAt": datetime.now(timezone.utc).isoformat(),
    }
    if error:
        result["error"] = error
    return result