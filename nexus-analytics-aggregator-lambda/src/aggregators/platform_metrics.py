# src/aggregators/platform_metrics.py
"""
Real-time platform-wide counters.

Key: PK=PLATFORM, SK=REALTIME
Single item, continuously updated.

High write contention from all concurrent Lambdas.
DynamoDB ADD is atomic — correct tool for this pattern.
"""

import os
from datetime import datetime, timezone
from decimal import Decimal

import boto3

from utils.logging_config import log

_dynamo = boto3.resource("dynamodb")
_table = _dynamo.Table(os.environ["PLATFORM_METRICS_TABLE"])


def update(amount: Decimal, currency: str,
           is_debit: bool) -> None:
    volume = amount if is_debit else Decimal("0")
    try:
        _table.update_item(
            Key={"PK": "PLATFORM", "SK": "REALTIME"},
            UpdateExpression=(
                "ADD transactionsToday :one, "
                "    transactionsThisHour :one, "
                "    transactionsLastMinute :one, "
                "    volumeToday :volume "
                "SET updatedAt = :now"
            ),
            ExpressionAttributeValues={
                ":one":    Decimal("1"),
                ":volume": volume,
                ":now":    datetime.now(timezone.utc).isoformat(),
            },
        )
    except Exception as exc:
        # Platform metrics failure is non-fatal — log and continue
        log.warning("platform_metrics update failed "
                    "(non-fatal)", error=str(exc))