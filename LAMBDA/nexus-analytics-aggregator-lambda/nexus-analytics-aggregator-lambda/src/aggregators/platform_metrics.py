# src/aggregators/platform_metrics.py
"""
Real-time platform-wide counters.

Key: PK=PLATFORM#{shard}, SK=REALTIME
NUM_SHARDS items, continuously updated (see
06_NOSQL_MODELING_CHANGES.md Section 5).

High write contention from all concurrent Lambdas.
DynamoDB ADD is atomic — correct tool for this pattern, but atomic doesn't
mean distributed: a single fixed PK still puts every write on one physical
partition. Sharded by hash(user_id), NOT randomly — activeUserIds dedup
(below) needs the same user to always land on the same shard, or the same
user could be double-counted across shards when summed on read.

Readers MUST fan out across all NUM_SHARDS and sum/recompute derived
ratios (avgFraudScore, fraudBlockRateToday) from the summed numerator/
denominator, not by averaging each shard's ratio - see
nexus-reporting-lambda's ReportDataQueryService.queryPlatformMetrics().
"""

import hashlib
import os
import random
from datetime import datetime, timezone
from decimal import Decimal

import boto3

from src.utils.logging_config import log

_dynamo = boto3.resource("dynamodb")
_table = _dynamo.Table(os.environ["PLATFORM_METRICS_TABLE"])

NUM_SHARDS = 10


def _shard_for_user(user_id: str) -> int:
    # Deterministic: the same user_id always maps to the same shard, which
    # is required for the activeUserIds ConditionExpression dedup below to
    # stay correct once writes are spread across shards.
    return int(hashlib.sha256(user_id.encode()).hexdigest(), 16) % NUM_SHARDS


def update(user_id: str, amount: Decimal, currency: str,
           is_debit: bool, fraud_score: float = None) -> None:
    pk = f"PLATFORM#{_shard_for_user(user_id)}" if user_id \
        else f"PLATFORM#{random.randint(0, NUM_SHARDS - 1)}"
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
            Key={"PK": pk, "SK": "REALTIME"},
            UpdateExpression=add_parts + " SET updatedAt = :now",
            ExpressionAttributeValues=expr_values,
        )
    except Exception as exc:
        log.warning("platform_metrics update failed (non-fatal)", error=str(exc))

    # Track active unique users via StringSet deduplication (non-fatal).
    # Same `pk` as above - deterministic shard keeps this user's set +
    # counters together for the dedup check to work.
    if user_id:
        try:
            _table.update_item(
                Key={"PK": pk, "SK": "REALTIME"},
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
    """Increment failedTransactionsToday — used to compute fraudBlockRateToday.

    No user_id available here, so no dedup concern - random shard is fine,
    the total is still correct once readers sum across all shards.
    """
    try:
        _table.update_item(
            Key={"PK": f"PLATFORM#{random.randint(0, NUM_SHARDS - 1)}",
                 "SK": "REALTIME"},
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