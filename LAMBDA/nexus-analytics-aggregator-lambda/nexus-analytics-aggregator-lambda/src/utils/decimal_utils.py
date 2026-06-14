# src/utils/decimal_utils.py
"""
Safe Decimal arithmetic for financial calculations.

NEVER use float for currency — float cannot represent 0.1 exactly
in binary (IEEE 754). Decimal(str(float)) avoids binary rounding.

All DynamoDB numeric values arrive as strings in the stream record.
parse_decimal() handles str, int, float, Decimal, and None safely.
"""

from decimal import Decimal, InvalidOperation, ROUND_HALF_UP


ZERO = Decimal("0")
ONE = Decimal("1")
NEG_ONE = Decimal("-1")


def parse_decimal(value) -> Decimal:
    """
    Convert any value to Decimal.
    Returns Decimal('0') on any parse error — safe fallback for analytics.
    """
    if value is None:
        return ZERO
    try:
        if isinstance(value, Decimal):
            return value
        if isinstance(value, (int, str)):
            return Decimal(str(value))
        if isinstance(value, float):
            # Via string avoids IEEE 754 representation errors
            return Decimal(str(round(value, 6)))
        return ZERO
    except (InvalidOperation, ValueError):
        return ZERO


def round_financial(value: Decimal, places: int = 2) -> Decimal:
    """Round to financial precision (ROUND_HALF_UP, not banker's rounding)."""
    quantizer = Decimal("0." + "0" * places)
    return value.quantize(quantizer, rounding=ROUND_HALF_UP)


def negate(value: Decimal) -> Decimal:
    """Return negative value for ADD-based decrements in DynamoDB."""
    return value * NEG_ONE