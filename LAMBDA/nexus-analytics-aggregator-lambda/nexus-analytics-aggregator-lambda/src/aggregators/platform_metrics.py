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


def update(user_id: str, amount: Decimal, currency: str,
           is_debit: bool, fraud_score: float = None) -> None:
    volume = amount if is_debit else Decimal("0")
    try:
        add_parts = (
            "ADD transactionsToday :one, "
            "    transactionsThisHour :one, "
            "    transactionsLastMinute :one, "
            "    volumeToday :volume"
        )
        expr_values = {
            ":one":    Decimal("1"),
            ":volume": volume,
            ":now":    datetime.now(timezone.utc).isoformat(),
        }
        if fraud_score is not None and fraud_score >= 0:
            add_parts += ", totalFraudScoreSum :fscore, fraudScoredCount :one"
            expr_values[":fscore"] = Decimal(str(round(fraud_score, 6)))

        _table.update_item(
            Key={"PK": "PLATFORM", "SK": "REALTIME"},
            UpdateExpression=add_parts + " SET updatedAt = :now",
            ExpressionAttributeValues=expr_values,
        )
    except Exception as exc:
        log.warning("platform_metrics update failed (non-fatal)", error=str(exc))

    # Track active unique users via StringSet deduplication (non-fatal)
    if user_id:
        try:
            _table.update_item(
                Key={"PK": "PLATFORM", "SK": "REALTIME"},
                UpdateExpression="ADD activeUsersToday :one, activeUserIds :uid_set",
                ConditionExpression="NOT contains(activeUserIds, :uid)",
                ExpressionAttributeValues={
                    ":one":     Decimal("1"),
                    ":uid_set": {user_id},
                    ":uid":     user_id,
                },
            )
        except _dynamo.meta.client.exceptions \
                .ConditionalCheckFailedException:
            pass  # User already counted today
        except Exception as exc:
            log.warning("platform_metrics active users update failed "
                        "(non-fatal)", error=str(exc))


def track_failed() -> None:
    """Increment failedTransactionsToday — used to compute fraudBlockRateToday."""
    try:
        _table.update_item(
            Key={"PK": "PLATFORM", "SK": "REALTIME"},
            UpdateExpression=(
                "ADD failedTransactionsToday :one "
                "SET updatedAt = :now"
            ),
            ExpressionAttributeValues={
                ":one": Decimal("1"),
                ":now": datetime.now(timezone.utc).isoformat(),
            },
        )
    except Exception as exc:
        log.warning("platform_metrics track_failed failed (non-fatal)",
                    error=str(exc))