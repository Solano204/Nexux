# src/aggregators/category_stats.py
"""
Monthly spending by merchant category per user.

Key: PK=USER#{userId}, SK=MONTH#{YYYY-MM}#CAT#{category}

Top-merchant tracking uses a conditional update:
  only overwrite topMerchantName if new amount > stored topMerchantAmount.
  ConditionalCheckFailed means another invocation just updated it — safe to ignore.
"""

import os
from datetime import datetime, timezone
from decimal import Decimal

import boto3

from utils.decimal_utils import parse_decimal, negate
from utils.mcc_mapper import mcc_to_category_name
from utils.logging_config import log

_dynamo = boto3.resource("dynamodb")
_table = _dynamo.Table(os.environ["CATEGORY_STATS_TABLE"])

_12_MONTHS_SEC = 365 * 86_400


def update(user_id: str, year_month: str, category: str,
           mcc: str, amount: Decimal, currency: str,
           merchant_name: str, sequence_number: str) -> None:

    ttl = _ttl_from_month(year_month, _12_MONTHS_SEC)
    category_name = mcc_to_category_name(mcc)
    now = datetime.now(timezone.utc).isoformat()

    try:
        response = _table.update_item(
            Key={
                "PK": f"USER#{user_id}",
                "SK": f"MONTH#{year_month}#CAT#{category}",
            },
            UpdateExpression=(
                "ADD totalAmount :amount, transactionCount :one "
                "SET updatedAt = :now, "
                "    categoryName = :cat_name, "
                "    currency = :currency, "
                "    userId = :user_id, "
                "    #ttl = :ttl, "
                "    lastSequenceNumber = :seq"
            ),
            ConditionExpression=(
                "attribute_not_exists(lastSequenceNumber) "
                "OR lastSequenceNumber < :seq"
            ),
            ExpressionAttributeValues={
                ":amount":   amount,
                ":one":      Decimal("1"),
                ":now":      now,
                ":cat_name": category_name,
                ":currency": currency,
                ":user_id":  user_id,
                ":ttl":      ttl,
                ":seq":      sequence_number,
            },
            ExpressionAttributeNames={"#ttl": "ttl"},
            ReturnValues="ALL_NEW",
        )

        # Update top merchant if this transaction exceeds stored top
        stored_top = parse_decimal(
            str(response.get("Attributes", {})
                .get("topMerchantAmount", "0")))
        if amount > stored_top:
            _try_update_top_merchant(
                user_id, year_month, category,
                merchant_name, amount)

    except _dynamo.meta.client.exceptions \
            .ConditionalCheckFailedException:
        log.debug("Duplicate — category stats skipped",
                  user_id=user_id, seq=sequence_number)
    except Exception as exc:
        log.error("category_stats update failed",
                  user_id=user_id, error=str(exc))
        raise


def reverse(user_id: str, year_month: str, category: str,
            amount: Decimal, sequence_number: str) -> None:
    neg = negate(amount)
    try:
        _table.update_item(
            Key={
                "PK": f"USER#{user_id}",
                "SK": f"MONTH#{year_month}#CAT#{category}",
            },
            UpdateExpression=(
                "ADD totalAmount :neg_amount, "
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
        log.debug("Duplicate reversal — category skip",
                  seq=sequence_number)
    except Exception as exc:
        log.error("category_stats reverse failed", error=str(exc))
        raise


def _try_update_top_merchant(user_id: str, year_month: str,
                             category: str, merchant_name: str,
                             amount: Decimal) -> None:
    try:
        _table.update_item(
            Key={
                "PK": f"USER#{user_id}",
                "SK": f"MONTH#{year_month}#CAT#{category}",
            },
            UpdateExpression=(
                "SET topMerchantName = :merchant, "
                "    topMerchantAmount = :amount"
            ),
            ConditionExpression=(
                "attribute_not_exists(topMerchantAmount) "
                "OR topMerchantAmount < :amount"
            ),
            ExpressionAttributeValues={
                ":merchant": merchant_name,
                ":amount":   amount,
            },
        )
    except _dynamo.meta.client.exceptions \
            .ConditionalCheckFailedException:
        pass  # Another invocation beat us — fine, analytics is best-effort


def _ttl_from_month(year_month: str, offset_seconds: int) -> int:
    try:
        d = datetime.strptime(year_month + "-01", "%Y-%m-%d")
        return int(d.replace(tzinfo=timezone.utc).timestamp()
                   + offset_seconds)
    except ValueError:
        return int(datetime.now(timezone.utc).timestamp()
                   + offset_seconds)