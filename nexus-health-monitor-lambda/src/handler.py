# src/handler.py
"""
Health Monitor Lambda — CloudWatch Events trigger (rate 5 minutes).

Complete execution flow:
1.  Run health checks (parallel via ThreadPoolExecutor)
2.  Publish metrics to CloudWatch (all results)
3.  For each unhealthy service:
    a. Get consecutive failure count (CloudWatch history)
    b. Decide whether to alert (threshold by criticality)
    c. Update CloudWatch Alarm
    d. Send SNS alert if threshold met
4.  For each healthy service:
    a. Check if it was previously DOWN (failure count > 0)
    b. If recovering: send all-clear SNS + reset alarm + reset counter
5.  Analyze platform-wide scenarios (cascade, infra down, all-tx-down)
6.  Send scenario alerts for critical combinations
7.  Maintain CloudWatch dashboard (idempotent)
8.  Return summary dict

State management:
  CloudWatch metrics ARE the state. No separate database.
  Consecutive failure count = read last N ServiceHealthStatus datapoints.
  This works because Lambda runs every 5 minutes with 5-minute period metrics.

Error handling:
  Health check failures per service: caught, produces DOWN result.
  CloudWatch publish failures: logged, non-fatal.
  SNS publish failures: logged, non-fatal.
  The Lambda always completes and returns a summary.
  Lambda-level failures are monitored by HealthMonitorLambdaErrors alarm.
"""

import os
import time
from datetime import datetime, timezone

from analysis.alert_decider import (
    get_consecutive_failures,
    should_alert,
)
from analysis.scenario_analyzer import analyze as analyze_scenarios
from alerting.message_builder import build_alert, build_recovery
from alerting.sns_alerter import send_alert
from checks.health_checker import run_checks_parallel, run_checks_sequential
from cloudwatch.alarm_manager import update_alarm, set_alarm_ok
from cloudwatch.dashboard_manager import ensure_dashboard
from cloudwatch.metrics_publisher import (
    publish_all,
    record_failure_count,
    reset_failure_count,
)
from registry.service_registry import (
    SERVICES,
    ALERT_TOPIC_BY_CRITICALITY,
)
from utils.logging_config import log

USE_PARALLEL = os.environ.get(
    "PARALLEL_CHECKS", "true").lower() == "true"


def check_platform_health(event: dict, context) -> dict:
    """Lambda handler — invoked by CloudWatch Events every 5 minutes."""
    start = time.monotonic()
    timestamp = datetime.now(timezone.utc)

    log.info("Health check cycle started",
             services=len(SERVICES),
             parallel=USE_PARALLEL,
             timestamp=timestamp.isoformat())

    # ── Step 1: Run health checks ──────────────────────────────
    results = (run_checks_parallel(SERVICES)
               if USE_PARALLEL
               else run_checks_sequential(SERVICES))

    # ── Step 2: Publish metrics ────────────────────────────────
    publish_all(results, timestamp)

    # ── Summarize ─────────────────────────────────────────────
    up_results = [r for r in results if r["status"] == "UP"]
    not_up = [r for r in results if r["status"] != "UP"]
    degraded = [r for r in results if r["status"] == "DEGRADED"]
    down = [r for r in results if r["status"] == "DOWN"]

    log.info("Health check results",
             healthy=len(up_results),
             degraded=len(degraded),
             down=len(down))

    alerts_sent = 0
    recoveries = 0

    # ── Step 3: Process unhealthy services ────────────────────
    for r in not_up:
        name = r["name"]
        criticality = r["criticality"]
        consecutive = get_consecutive_failures(name)
        new_count = consecutive + 1

        # Record the new failure count
        record_failure_count(name, new_count, timestamp)

        # Update CloudWatch Alarm
        update_alarm(name, criticality, r["status"])

        log.warning("Unhealthy service",
                    service=name,
                    status=r["status"],
                    detail=r.get("statusDetail"),
                    consecutive_failures=new_count,
                    criticality=criticality)

        if should_alert(r, new_count):
            is_new = consecutive == 0
            payload = build_alert(r, new_count, is_new)
            topic = ALERT_TOPIC_BY_CRITICALITY.get(
                criticality, "standard")
            if send_alert(payload, topic):
                alerts_sent += 1

    # ── Step 4: Process recoveries ─────────────────────────────
    for r in up_results:
        name = r["name"]
        # Read failure count BEFORE this check (subtract the current UP)
        prev_failures = get_consecutive_failures(name)

        if prev_failures >= 1:
            # Service was down, now back UP
            log.info("Service recovered",
                     service=name,
                     previous_failures=prev_failures,
                     estimated_downtime_min=prev_failures * 5)

            reset_failure_count(name, timestamp)
            set_alarm_ok(name)

            payload = build_recovery(r, prev_failures)
            topic = ALERT_TOPIC_BY_CRITICALITY.get(
                r["criticality"], "standard")
            if send_alert(payload, topic):
                recoveries += 1

    # ── Step 5-6: Platform scenario analysis ───────────────────
    scenarios = analyze_scenarios(results)
    for scenario in scenarios:
        severity = scenario.get("severity", "STANDARD")
        topic = ("critical" if severity == "CRITICAL"
                 else "standard")
        if send_alert(scenario, topic):
            alerts_sent += 1

        log.warning("Platform scenario detected",
                    scenario=scenario.get("scenarioType"),
                    severity=severity)

    # ── Step 7: Maintain CloudWatch dashboard ──────────────────
    try:
        ensure_dashboard(SERVICES)
    except Exception as exc:
        log.warning("Dashboard update failed (non-fatal)",
                    error=str(exc))

    duration_ms = int((time.monotonic() - start) * 1000)

    log.info("Health check cycle complete",
             healthy=len(up_results),
             degraded=len(degraded),
             down=len(down),
             alerts_sent=alerts_sent,
             recoveries=recoveries,
             scenarios=len(scenarios),
             duration_ms=duration_ms)

    return {
        "timestamp": timestamp.isoformat(),
        "totalServices": len(results),
        "healthy": len(up_results),
        "degraded": len(degraded),
        "down": len(down),
        "alertsSent": alerts_sent,
        "recoveries": recoveries,
        "scenarios": len(scenarios),
        "durationMs": duration_ms,
    }