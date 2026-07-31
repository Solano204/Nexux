# src/aggregators/hourly_volume.py
"""
Platform-wide transaction volume by hour.

Key: PK=PLATFORM#{shard}, SK=HOUR#{YYYY-MM-DD}T{HH}
NUM_SHARDS items per hour, sharded to spread write contention across
partitions (see 06_NOSQL_MODELING_CHANGES.md Section 5 - a fixed PK="PLATFORM"
put 100% of the platform's write traffic on one physical partition).

No user_id is available in this function's inputs, so there's no dedup
concern to preserve (unlike platform_metrics.py's activeUserIds) - a random
shard per write is correct: these are pure ADD counters, summing across
shards gives the right total regardless of which shard absorbed which write.

Readers MUST fan out across all NUM_SHARDS and sum - see
nexus-reporting-lambda's ReportDataQueryService.queryHourlyVolumes().
"""

import os
import random
from datetime import datetime, timezone
from decimal import Decimal

import boto3

from src.utils.logging_config import log

_dynamo = boto3.resource("dynamodb")
_table = _dynamo.Table(os.environ["HOURLY_VOLUME_TABLE"])

_30_DAYS_SEC = 30 * 86_400
NUM_SHARDS = 10


def update(hour_str: str, amount: Decimal,
           currency: str, payment_network: str) -> None:
    try:
        hour_dt = datetime.strptime(hour_str, "%Y-%m-%dT%H")
    except ValueError:
        log.warning("Invalid hour_str", hour_str=hour_str)
        return

    ttl = int(hour_dt.replace(tzinfo=timezone.utc).timestamp()
              + _30_DAYS_SEC)
    now = datetime.now(timezone.utc).isoformat()
    pk = f"PLATFORM#{random.randint(0, NUM_SHARDS - 1)}"

    # Main volume counters
    try:
        _table.update_item(
            Key={"PK": pk, "SK": f"HOUR#{hour_str}"},
            UpdateExpression=(
                "ADD totalVolume :amount, transactionCount :one "
                "SET updatedAt = :now, "
                "    #date = :date, "
                "    #hour = :hour, "
                "    #ttl = :ttl"
            ),
            ExpressionAttributeValues={
                ":amount": amount,
                ":one":    Decimal("1"),
                ":now":    now,
                ":date":   hour_dt.strftime("%Y-%m-%d"),
                ":hour":   hour_dt.hour,
                ":ttl":    ttl,
            },
            ExpressionAttributeNames={
                "#date": "date",
                "#hour": "hour",
                "#ttl":  "ttl",
            },
        )
    except Exception as exc:
        log.error("hourly_volume update failed",
                  hour=hour_str, error=str(exc))
        raise

    # Per-network volume (best-effort — separate update, same shard so it
    # lands on the same item as the counters above)
    network_attr = (
            "network_" + payment_network.lower().replace("-", "_") +
            "_volume"
    )
    count_attr = (
            "network_" + payment_network.lower().replace("-", "_") +
            "_count"
    )
    try:
        _table.update_item(
            Key={"PK": pk, "SK": f"HOUR#{hour_str}"},
            UpdateExpression=(
                f"ADD {network_attr} :amount, {count_attr} :one"
            ),
            ExpressionAttributeValues={
                ":amount": amount,
                ":one":    Decimal("1"),
            },
        )
    except Exception as exc:
        log.warning("Network volume breakdown update failed "
                    "(non-fatal)", network=payment_network,
                    error=str(exc))