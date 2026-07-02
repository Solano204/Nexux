# src/aggregators/user_summary.py
"""
Current-period MTD summary per user.
Primary read target for dashboard API queries.

Key: PK=USER#{userId}, SK=CURRENT
One permanent row per user, continuously updated.

Month boundary handling:
  Only add to mtdSpending if transaction is in current calendar month.
  Cross-month transactions (edge case) update lastTransactionAt only.
"""

import os
from datetime import datetime, timezone
from decimal import Decimal

import boto3

from utils.decimal_utils import negate
from utils.logging_config import log

_dynamo = boto3.resource("dynamodb")
_table = _dynamo.Table(os.environ["USER_SUMMARY_TABLE"])


def update(user_id: str, amount: Decimal, currency: str,
           year_month: str, is_debit: bool, is_credit: bool,
           completed_at: str, sequence_number: str) -> None:

    current_month = datetime.now(timezone.utc).strftime("%Y-%m")
    is_current_month = (year_month == current_month)
    now = datetime.now(timezone.utc).isoformat()

    add_parts = ["transactionCount :one"]
    expr_values: dict = {
        ":one":          Decimal("1"),
        ":completed_at": completed_at,
        ":currency":     currency,
        ":user_id":      user_id,
        ":now":          now,
        ":seq":          sequence_number,
    }

    if is_current_month:
        if is_debit:
            add_parts.append("mtdSpending :amount")
            expr_values[":amount"] = amount
        elif is_credit:
            add_parts.append("mtdIncome :amount")
            expr_values[":amount"] = amount

    update_expr = (
            "ADD " + ", ".join(add_parts) + " "
                                            "SET lastTransactionAt = :completed_at, "
                                            "    currency = :currency, "
                                            "    userId = :user_id, "
                                            "    updatedAt = :now, "
                                            "    lastSequenceNumber = :seq"
    )

    try:
        _table.update_item(
            Key={"PK": f"USER#{user_id}", "SK": "CURRENT"},
            UpdateExpression=update_expr,
            ConditionExpression=(
                "attribute_not_exists(lastSequenceNumber) "
                "OR lastSequenceNumber < :seq"
            ),
            ExpressionAttributeValues=expr_values,
        )
    except _dynamo.meta.client.exceptions \
            .ConditionalCheckFailedException:
        log.debug("Duplicate — user summary skipped",
                  user_id=user_id, seq=sequence_number)
    except Exception as exc:
        log.error("user_summary update failed",
                  user_id=user_id, error=str(exc))
        raise


def reverse(user_id: str, amount: Decimal, year_month: str,
            sequence_number: str) -> None:
    current_month = datetime.now(timezone.utc).strftime("%Y-%m")
    if year_month != current_month:
        return  # No MTD impact from prior-month reversals

    neg = negate(amount)
    try:
        _table.update_item(
            Key={"PK": f"USER#{user_id}", "SK": "CURRENT"},
            UpdateExpression=(
                "ADD mtdSpending :neg_amount, "
                "    transactionCount :neg_one "
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
        log.debug("Duplicate reversal — user summary skipped",
                  seq=sequence_number)
    except Exception as exc:
        log.error("user_summary reverse failed", error=str(exc))
        raise