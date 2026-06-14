# src/aggregators/merchant_frequency.py
"""
Per-user merchant visit counts (rolling 30 days via TTL).

Key: PK=USER#{userId}, SK=MERCHANT#{merchantId}

TTL 35 days: 5-day buffer over the 30-day window.
When TTL expires, the item is deleted; the next visit creates it fresh.
"""

import os
from datetime import datetime, timezone
from decimal import Decimal

import boto3

from utils.logging_config import log

_dynamo = boto3.resource("dynamodb")
_table = _dynamo.Table(os.environ["MERCHANT_FREQUENCY_TABLE"])

_35_DAYS_SEC = 35 * 86_400


def update(user_id: str, merchant_id: str, merchant_name: str,
           mcc: str, amount: Decimal, currency: str,
           date_str: str) -> None:
    try:
        d = datetime.strptime(date_str, "%Y-%m-%d")
        ttl = int(d.replace(tzinfo=timezone.utc).timestamp()
                  + _35_DAYS_SEC)
    except ValueError:
        ttl = int(datetime.now(timezone.utc).timestamp()
                  + _35_DAYS_SEC)

    try:
        _table.update_item(
            Key={
                "PK": f"USER#{user_id}",
                "SK": f"MERCHANT#{merchant_id}",
            },
            UpdateExpression=(
                "ADD visitCount :one, totalAmount :amount "
                "SET merchantName = :name, "
                "    merchantCategoryCode = :mcc, "
                "    lastVisitDate = :date, "
                "    currency = :currency, "
                "    userId = :user_id, "
                "    updatedAt = :now, "
                "    #ttl = :ttl"
            ),
            ExpressionAttributeValues={
                ":one":     Decimal("1"),
                ":amount":  amount,
                ":name":    merchant_name,
                ":mcc":     mcc,
                ":date":    date_str,
                ":currency": currency,
                ":user_id": user_id,
                ":now":     datetime.now(timezone.utc).isoformat(),
                ":ttl":     ttl,
            },
            ExpressionAttributeNames={"#ttl": "ttl"},
        )
    except Exception as exc:
        log.error("merchant_frequency update failed",
                  user_id=user_id, merchant_id=merchant_id,
                  error=str(exc))
        raise