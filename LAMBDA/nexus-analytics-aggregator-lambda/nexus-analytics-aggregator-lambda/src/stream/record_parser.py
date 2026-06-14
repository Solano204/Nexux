# src/stream/record_parser.py
"""
Deserializes DynamoDB Stream records from typed JSON to plain Python dicts.

DynamoDB Stream format:
  {"userId": {"S": "abc123"}, "amount": {"N": "150.00"}, ...}

After deserialization:
  {"userId": "abc123", "amount": Decimal("150.00"), ...}

TypeDeserializer handles all DynamoDB types:
  S → str, N → Decimal, BOOL → bool, NULL → None,
  L → list, M → dict, SS → set, NS → set, BS → set
"""

from typing import Optional
from boto3.dynamodb.types import TypeDeserializer

_deserializer = TypeDeserializer()


def parse_new_image(record: dict) -> Optional[dict]:
    """Extract and deserialize the NewImage from a stream record."""
    new_image = record.get("dynamodb", {}).get("NewImage")
    if not new_image:
        return None
    return _deserialize(new_image)


def parse_old_image(record: dict) -> Optional[dict]:
    """Extract and deserialize the OldImage from a stream record."""
    old_image = record.get("dynamodb", {}).get("OldImage")
    if not old_image:
        return None
    return _deserialize(old_image)


def parse_sequence_number(record: dict) -> str:
    """Extract DynamoDB sequence number for idempotency tracking."""
    return record.get("dynamodb", {}).get("SequenceNumber", "")


def is_transaction_record(item: dict) -> bool:
    """
    True if this DynamoDB item represents a financial transaction.
    The transactions table may contain other item types
    (e.g., session records) — filter them out here.
    """
    return (
            item is not None
            and "transactionId" in item
            and "userId" in item
            and "amount" in item
            and "status" in item
    )


def _deserialize(dynamo_item: dict) -> dict:
    return {
        key: _deserializer.deserialize(value)
        for key, value in dynamo_item.items()
    }