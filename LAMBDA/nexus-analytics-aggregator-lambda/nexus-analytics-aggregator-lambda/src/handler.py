# src/handler.py
"""
Lambda entry point — DynamoDB Streams trigger.

Processing pipeline per stream record:
1. Deserialize NewImage + OldImage from typed DynamoDB JSON
2. Filter non-transaction records (sessions, etc.)
3. Detect state transition (COMPLETED, FAILED, REVERSED, NEW)
4. Dispatch to appropriate aggregator(s)
5. Collect CloudWatch metrics for batch emit

Error handling:
- Any exception in process_record() is re-raised.
  DynamoDB Streams retries the batch.
  BisectBatchOnFunctionError halves the batch on each retry,
  eventually isolating the bad record to a batch of 1 → DLQ.

Idempotency:
  All aggregators use ConditionExpression on lastSequenceNumber.
  Duplicate stream records (at-least-once delivery) are detected
  and skipped at the aggregator level.

DynamoDB ADD atomicity:
  All increments use ADD, never read-modify-write.
  Concurrent Lambda invocations update the same item safely.
"""

import os
import time
from datetime import datetime, timezone
from decimal import Decimal

import boto3

from src.aggregators import (
    daily_stats,
    category_stats,
    hourly_volume,
    merchant_frequency,
    user_summary,
    platform_metrics,
)
from src.stream.record_parser import (
    parse_new_image,
    parse_old_image,
    parse_sequence_number,
    is_transaction_record,
)
from src.stream.transition_detector import detect_transition
from src.utils.decimal_utils import parse_decimal
from src.utils.logging_config import log
from src.utils.metrics import build_metric

_cw = boto3.client("cloudwatch",
                   region_name=os.environ.get("AWS_REGION_NAME",
                                              "us-east-1"))
_CW_NS = os.environ.get("CLOUDWATCH_NAMESPACE", "Nexus/Analytics")

# Transaction types that represent outflows (spending)
# Matches TransactionType enum: PAYMENT, EXTERNAL_TRANSFER, INTERNAL_TRANSFER,
# FEE, CASH_OUT. REVERSAL status changes are handled via _handle_reversed().
_DEBIT_TYPES = frozenset([
    "PAYMENT", "EXTERNAL_TRANSFER", "INTERNAL_TRANSFER",
    "FEE", "CASH_OUT",
])
# Transaction types that represent inflows (income)
# Matches TransactionType enum: DIRECT_DEPOSIT, CASH_IN, INTEREST.
_CREDIT_TYPES = frozenset([
    "DIRECT_DEPOSIT", "CASH_IN", "INTEREST",
])


def aggregate_transactions(event: dict, context) -> dict:
    """Lambda handler — DynamoDB Streams entry point."""
    records = event.get("Records", [])
    batch_start = time.monotonic()

    log.info("DynamoDB Stream batch received",
             record_count=len(records),
             request_id=context.aws_request_id)

    processed = 0
    skipped = 0
    cw_metrics: list[dict] = []

    for record in records:
        seq = parse_sequence_number(record)
        try:
            result = _process_record(record)
            if result["processed"]:
                processed += 1
                cw_metrics.extend(result.get("metrics", []))
            else:
                skipped += 1
        except Exception as exc:
            log.error("Stream record processing failed",
                      sequence_number=seq,
                      event_name=record.get("eventName"),
                      error=str(exc))
            raise  # Trigger DynamoDB Streams retry + bisect

    duration_ms = int((time.monotonic() - batch_start) * 1000)

    # Batch-emit CloudWatch metrics (max 20 per API call)
    _emit_metrics(cw_metrics)

    # Emit aggregation latency metric
    _cw.put_metric_data(
        Namespace=_CW_NS,
        MetricData=[build_metric(
            "AggregationBatchDuration", duration_ms,
            "Milliseconds", [])],
    )

    log.info("Batch processing complete",
             processed=processed, skipped=skipped,
             duration_ms=duration_ms)

    return {"processed": processed, "skipped": skipped}


def _process_record(record: dict) -> dict:
    """Process a single DynamoDB Stream record."""

    event_name = record.get("eventName")
    seq = parse_sequence_number(record)

    # Belt-and-suspenders: filter criteria in template.yaml handles this,
    # but guard here too
    if event_name not in ("INSERT", "MODIFY"):
        return {"processed": False, "reason": f"skip:{event_name}"}

    new_image = parse_new_image(record)
    old_image = parse_old_image(record)

    if new_image is None or not is_transaction_record(new_image):
        return {"processed": False, "reason": "not_a_transaction"}

    transition = detect_transition(old_image, new_image)
    if transition is None:
        return {"processed": False, "reason": "no_meaningful_transition"}

    # Extract common fields
    user_id      = str(new_image.get("userId", ""))
    amount       = parse_decimal(new_image.get("amount", "0"))
    currency     = str(new_image.get("currency", "MXN"))
    category     = str(new_image.get("category", "UNCATEGORIZED"))
    mcc          = str(new_image.get("merchantCategoryCode", "5999"))
    merchant_id  = str(new_image.get("merchantId", ""))
    merchant_name = str(new_image.get("merchantName", ""))
    txn_type     = str(new_image.get("transactionType", "UNKNOWN")).upper()
    network      = str(new_image.get("paymentNetwork", "INTERNAL"))
    completed_at = str(new_image.get(
        "completedAt",
        datetime.now(timezone.utc).isoformat()))
    fraud_score_raw = new_image.get("fraudScore")
    fraud_score = float(fraud_score_raw) \
        if fraud_score_raw is not None else None

    is_debit  = txn_type in _DEBIT_TYPES
    is_credit = txn_type in _CREDIT_TYPES

    if not user_id or amount <= Decimal("0"):
        return {"processed": False, "reason": "invalid_user_or_amount"}

    # Parse completed_at for date/time components
    try:
        completed_dt = datetime.fromisoformat(
            completed_at.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        completed_dt = datetime.now(timezone.utc)

    date_str   = completed_dt.strftime("%Y-%m-%d")
    year_month = completed_dt.strftime("%Y-%m")
    hour_str   = completed_dt.strftime("%Y-%m-%dT%H")

    metrics: list[dict] = []

    if transition == "COMPLETED":
        metrics = _handle_completed(
            user_id, amount, currency, category, mcc,
            merchant_id, merchant_name, txn_type, network,
            is_debit, is_credit,
            date_str, year_month, hour_str, completed_at, fraud_score, seq)

    elif transition == "REVERSED":
        metrics = _handle_reversed(
            user_id, amount, currency, category,
            date_str, year_month, seq)

    elif transition == "FAILED":
        metrics = _handle_failed(user_id, date_str, seq, currency, network)

    elif transition == "NEW_TRANSACTION":
        # Minimal tracking for pending transactions
        log.debug("New transaction inserted",
                  user_id=user_id, amount=str(amount))

    return {"processed": True, "metrics": metrics}


def _handle_completed(
        user_id: str, amount: Decimal, currency: str,
        category: str, mcc: str,
        merchant_id: str, merchant_name: str,
        txn_type: str, network: str,
        is_debit: bool, is_credit: bool,
        date_str: str, year_month: str, hour_str: str,
        completed_at: str, fraud_score: float, seq: str) -> list[dict]:

    daily_stats.update(
        user_id, date_str, amount, currency,
        is_debit, is_credit, completed_at, seq)

    if is_debit and category:
        category_stats.update(
            user_id, year_month, category, mcc,
            amount, currency, merchant_name, seq)

    hourly_volume.update(hour_str, amount, currency, network)

    if is_debit and merchant_id and merchant_name:
        merchant_frequency.update(
            user_id, merchant_id, merchant_name,
            mcc, amount, currency, date_str)

    user_summary.update(
        user_id, amount, currency, year_month,
        is_debit, is_credit, completed_at, seq)

    platform_metrics.update(user_id, amount, currency, is_debit, fraud_score)

    return [
        build_metric("TransactionsCompleted", 1.0, "Count",
                     [("Currency", currency), ("Network", network)]),
        build_metric("TransactionVolume", float(amount), "None",
                     [("Currency", currency), ("Category", category)]),
    ]


def _handle_reversed(
        user_id: str, amount: Decimal, currency: str,
        category: str, date_str: str,
        year_month: str, seq: str) -> list[dict]:

    daily_stats.reverse(user_id, date_str, amount, seq)

    if category:
        category_stats.reverse(
            user_id, year_month, category, amount, seq)

    user_summary.reverse(user_id, amount, year_month, seq)

    log.info("Transaction reversed — aggregations decremented",
             user_id=user_id, amount=str(amount), date=date_str)

    return [
        build_metric("TransactionsReversed", 1.0, "Count",
                     [("Currency", currency)])
    ]


def _handle_failed(user_id: str, date_str: str, seq: str,
                   currency: str, network: str) -> list[dict]:
    daily_stats.fail(user_id, date_str, seq)
    platform_metrics.track_failed()
    return [
        build_metric("TransactionsFailed", 1.0, "Count",
                     [("Currency", currency), ("Network", network)])
    ]


def _emit_metrics(metrics: list[dict]) -> None:
    """Batch CloudWatch PutMetricData (max 20 per call)."""
    if not metrics:
        return
    for i in range(0, len(metrics), 20):
        chunk = metrics[i:i + 20]
        try:
            _cw.put_metric_data(Namespace=_CW_NS, MetricData=chunk)
        except Exception as exc:
            log.warning("CloudWatch metric emit failed (non-fatal)",
                        error=str(exc), count=len(chunk))