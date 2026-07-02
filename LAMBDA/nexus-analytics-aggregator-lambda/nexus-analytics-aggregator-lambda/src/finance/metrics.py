# src/finance/metrics.py
"""
Financial analytics CloudWatch metrics.

Builds domain-specific metric data for financial aggregation events.
Wraps the generic utils.metrics.build_metric with financial context:
currency dimensions, transaction type breakdowns, fraud score tracking.
"""
import os
from datetime import datetime, timezone
from decimal import Decimal

import boto3

CW_NAMESPACE = os.environ.get("CLOUDWATCH_NAMESPACE", "Nexus/Analytics")

_cloudwatch = None


def _get_cloudwatch():
    global _cloudwatch
    if _cloudwatch is None:
        _cloudwatch = boto3.client(
            "cloudwatch",
            region_name=os.environ.get("AWS_REGION", "us-east-1"),
        )
    return _cloudwatch


def emit_transaction_completed_metric(
        currency: str, payment_network: str, amount: Decimal) -> None:
    """Emit metrics when a transaction completes successfully."""
    _get_cloudwatch().put_metric_data(
        Namespace=CW_NAMESPACE,
        MetricData=[
            {
                "MetricName": "TransactionsCompleted",
                "Value": 1.0,
                "Unit": "Count",
                "Timestamp": datetime.now(timezone.utc),
                "Dimensions": [
                    {"Name": "Currency", "Value": currency},
                    {"Name": "Network", "Value": payment_network},
                ],
            },
            {
                "MetricName": "TransactionVolume",
                "Value": float(amount),
                "Unit": "None",
                "Timestamp": datetime.now(timezone.utc),
                "Dimensions": [
                    {"Name": "Currency", "Value": currency},
                ],
            },
        ],
    )


def emit_transaction_failed_metric(currency: str) -> None:
    """Emit metric when a transaction fails."""
    _get_cloudwatch().put_metric_data(
        Namespace=CW_NAMESPACE,
        MetricData=[
            {
                "MetricName": "TransactionsFailed",
                "Value": 1.0,
                "Unit": "Count",
                "Timestamp": datetime.now(timezone.utc),
                "Dimensions": [
                    {"Name": "Currency", "Value": currency},
                ],
            }
        ],
    )


def emit_reversal_metric(currency: str) -> None:
    """Emit metric when a completed transaction is reversed."""
    _get_cloudwatch().put_metric_data(
        Namespace=CW_NAMESPACE,
        MetricData=[
            {
                "MetricName": "TransactionsReversed",
                "Value": 1.0,
                "Unit": "Count",
                "Timestamp": datetime.now(timezone.utc),
                "Dimensions": [
                    {"Name": "Currency", "Value": currency},
                ],
            }
        ],
    )


def emit_aggregation_latency(latency_ms: float) -> None:
    """Emit aggregation processing latency."""
    _get_cloudwatch().put_metric_data(
        Namespace=CW_NAMESPACE,
        MetricData=[
            {
                "MetricName": "AggregationLatency",
                "Value": latency_ms,
                "Unit": "Milliseconds",
                "Timestamp": datetime.now(timezone.utc),
            }
        ],
    )
