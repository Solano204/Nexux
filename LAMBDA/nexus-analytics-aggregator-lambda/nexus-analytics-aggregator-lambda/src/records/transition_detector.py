# src/records/transition_detector.py
"""
DynamoDB Stream record state transition detector.

Determines what kind of financial state change occurred when a
DynamoDB transaction item is inserted or modified.

State machine:
  None → PENDING/FRAUD_CHECKING  = NEW_TRANSACTION
  PENDING → COMPLETED            = COMPLETED
  FRAUD_CHECKING → COMPLETED     = COMPLETED
  PENDING → FAILED               = FAILED
  FRAUD_CHECKING → FAILED        = FAILED
  COMPLETED → REVERSED           = REVERSED
  * → * (same status)            = None (no meaningful change)

This module is a standalone alternative to stream.transition_detector
for use cases that process raw DynamoDB records outside the stream
context (e.g., batch reprocessing, data migration scripts).
"""
from typing import Optional


def detect_record_transition(
        old_image: Optional[dict],
        new_image: dict) -> Optional[str]:
    """
    Detect financial state transition from old/new DynamoDB images.

    Args:
        old_image: Previous item state (None for INSERT)
        new_image: Current item state

    Returns:
        Transition type string or None if no meaningful change:
        'NEW_TRANSACTION', 'COMPLETED', 'FAILED', 'REVERSED'
    """
    new_status = new_image.get("status", "")
    old_status = old_image.get("status", "") if old_image else None

    if old_image is None:
        if new_status in ("PENDING", "FRAUD_CHECKING"):
            return "NEW_TRANSACTION"
        if new_status == "COMPLETED":
            return "COMPLETED"
        return None

    if new_status == old_status:
        return None

    if new_status == "COMPLETED" and old_status not in ("COMPLETED", "REVERSED"):
        return "COMPLETED"

    if new_status == "FAILED" and old_status != "FAILED":
        return "FAILED"

    if new_status == "REVERSED" and old_status == "COMPLETED":
        return "REVERSED"

    return None


def is_financial_record(item: dict) -> bool:
    """Check if a DynamoDB item represents a financial transaction."""
    required = ("transactionId", "userId", "amount", "status")
    return all(item.get(k) is not None for k in required)


def extract_transition_context(
        old_image: Optional[dict],
        new_image: dict,
        transition: str) -> dict:
    """
    Extract context relevant to the detected transition.
    Used for logging and metrics enrichment.
    """
    return {
        "transition": transition,
        "userId": new_image.get("userId", ""),
        "transactionId": new_image.get("transactionId", ""),
        "oldStatus": old_image.get("status") if old_image else None,
        "newStatus": new_image.get("status", ""),
        "amount": str(new_image.get("amount", "0")),
        "currency": new_image.get("currency", "MXN"),
        "transactionType": new_image.get("transactionType", "UNKNOWN"),
        "paymentNetwork": new_image.get("paymentNetwork", "INTERNAL"),
    }
