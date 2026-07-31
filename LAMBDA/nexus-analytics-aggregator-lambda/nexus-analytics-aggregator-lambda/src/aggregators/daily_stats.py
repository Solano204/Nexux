# src/aggregators/daily_stats.py
"""
Daily spending and income totals per user.

Table: nexus-analytics-daily
Key: PK=USER#{userId}, SK=DATE#{YYYY-MM-DD}

Atomic ADD operations:
  totalSpent, totalReceived, transactionCount, completedCount
  → safe for concurrent Lambda invocations (no lost updates)

Idempotency via sequence numbers:
  ConditionExpression: lastSequenceNumber < :seq
  → duplicate stream records produce ConditionalCheckFailed → skip
"""

import os
from datetime import datetime, timezone
from decimal import Decimal

import boto3

from src.utils.decimal_utils import parse_decimal, negate
from src.utils.logging_config import log

_dynamo = boto3.resource("dynamodb")
_table = _dynamo.Table(os.environ["DAILY_STATS_TABLE"])

_90_DAYS_SEC = 90 * 86_400


def update(user_id: str, date_str: str, amount: Decimal,
           currency: str, is_debit: bool, is_credit: bool,
           completed_at: str, sequence_number: str) -> bool:
    """
    Idempotent daily stats update.
    Returns True if applied, False if duplicate (already processed).
    """
    ttl = _ttl(date_str, _90_DAYS_SEC)

    expr_values: dict = {
        ":one":     Decimal("1"),
        ":now":     completed_at,
        ":currency": currency,
        ":user_id": user_id,
        ":ttl":     ttl,
        ":seq":     sequence_number,
    }
    add_parts = ["transactionCount :one", "completedCount :one"]

    if is_debit:
        add_parts.append("totalSpent :amount")
        expr_values[":amount"] = amount
    elif is_credit:
        add_parts.append("totalReceived :amount")
        expr_values[":amount"] = amount
    else:
        # Fee or other — still count
        expr_values[":amount"] = Decimal("0")

    update_expr = (
            "ADD " + ", ".join(add_parts) + " "
                                            "SET lastTransactionAt = :now, "
                                            "    currency = :currency, "
                                            "    userId = :user_id, "
                                            "    #ttl = :ttl, "
                                            "    lastSequenceNumber = :seq"
    )

    try:
        _table.update_item(
            Key={"PK": f"USER#{user_id}", "SK": f"DATE#{date_str}"},
            UpdateExpression=update_expr,
            ConditionExpression=(
                "attribute_not_exists(lastSequenceNumber) "
                "OR lastSequenceNumber < :seq"
            ),
            ExpressionAttributeValues=expr_values,
            ExpressionAttributeNames={"#ttl": "ttl"},
        )
    except _dynamo.meta.client.exceptions \
            .ConditionalCheckFailedException:
        log.debug("Duplicate stream record — daily stats skipped",
                  user_id=user_id, seq=sequence_number)
        return False
    except Exception as exc:
        log.error("daily_stats update failed",
                  user_id=user_id, date=date_str, error=str(exc))
        raise

    # Track maxTransactionAmount with a conditional SET (non-atomic but best-effort)
    if amount > Decimal("0"):
        try:
            _table.update_item(
                Key={"PK": f"USER#{user_id}", "SK": f"DATE#{date_str}"},
                UpdateExpression="SET maxTransactionAmount = :amount",
                ConditionExpression=(
                    "attribute_not_exists(maxTransactionAmount) "
                    "OR maxTransactionAmount < :amount"
                ),
                ExpressionAttributeValues={":amount": amount},
            )
        except _dynamo.meta.client.exceptions \
                .ConditionalCheckFailedException:
            pass  # Stored max is already >= this amount
        except Exception as exc:
            log.warning("daily_stats maxTransactionAmount update failed "
                        "(non-fatal)", user_id=user_id, error=str(exc))

    return True


def reverse(user_id: str, date_str: str, amount: Decimal,
            sequence_number: str) -> None:
    """
    Subtract from daily totals when a completed transaction is reversed.
    DynamoDB ADD with negative Decimal is the correct atomic decrement.
    """
    neg = negate(amount)
    try:
        _table.update_item(
            Key={"PK": f"USER#{user_id}", "SK": f"DATE#{date_str}"},
            UpdateExpression=(
                "ADD totalSpent :neg_amount, "
                "    transactionCount :neg_one, "
                "    completedCount :neg_one "
                "SET updatedAt = :now, "
                "    lastSequenceNumber = :seq"
            ),
            ConditionExpression=(
                "attribute_not_exists(lastSequenceNumber) "
                "OR lastSequenceNumber < :seq"
            ),
            ExpressionAttributeValues={
                ":neg_amount": neg,
                ":neg_one":    Decimal("-1"),
                ":now":        datetime.now(timezone.utc).isoformat(),
                ":seq":        sequence_number,
            },
        )
    except _dynamo.meta.client.exceptions \
            .ConditionalCheckFailedException:
        log.debug("Duplicate reversal — daily stats skip",
                  user_id=user_id, seq=sequence_number)
    except Exception as exc:
        log.error("daily_stats reverse failed",
                  user_id=user_id, error=str(exc))
        raise


def fail(user_id: str, date_str: str, sequence_number: str) -> bool:
    """
    Increment failedCount when a transaction transitions to FAILED.
    Idempotent via sequence number guard.
    """
    ttl = _ttl(date_str, _90_DAYS_SEC)
    try:
        _table.update_item(
            Key={"PK": f"USER#{user_id}", "SK": f"DATE#{date_str}"},
            UpdateExpression=(
                "ADD failedCount :one "
                "SET userId = :user_id, #ttl = :ttl, "
                "    lastSequenceNumber = :seq"
            ),
            ConditionExpression=(
                "attribute_not_exists(lastSequenceNumber) "
                "OR lastSequenceNumber < :seq"
            ),
            ExpressionAttributeValues={
                ":one":     Decimal("1"),
                ":user_id": user_id,
                ":ttl":     ttl,
                ":seq":     sequence_number,
            },
            ExpressionAttributeNames={"#ttl": "ttl"},
        )
        return True
    except _dynamo.meta.client.exceptions \
            .ConditionalCheckFailedException:
        log.debug("Duplicate stream record — fail count skipped",
                  user_id=user_id, seq=sequence_number)
        return False
    except Exception as exc:
        log.error("daily_stats fail update failed",
                  user_id=user_id, date=date_str, error=str(exc))
        raise


def _ttl(date_str: str, offset_seconds: int) -> int:
    try:
        d = datetime.strptime(date_str, "%Y-%m-%d")
        return int(d.replace(tzinfo=timezone.utc).timestamp()
                   + offset_seconds)
    except ValueError:
        return int(datetime.now(timezone.utc).timestamp()
                   + offset_seconds)