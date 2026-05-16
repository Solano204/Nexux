# src/stream/transition_detector.py
"""
Detect meaningful financial state transitions from DynamoDB Stream records.

State machine:
  PENDING       → FRAUD_CHECKING   → NEW_TRANSACTION (track count only)
  FRAUD_CHECKING → COMPLETED       → COMPLETED (aggregate all stats)
  FRAUD_CHECKING → FAILED          → FAILED (increment failure counter)
  COMPLETED     → REVERSED         → REVERSED (subtract from aggregations)

"Meaningful" means the status changed in a way that affects financial
aggregations. Metadata-only updates (description change, tag update)
produce transition=None and are skipped.
"""

from typing import Optional


# Statuses that indicate a transaction has added to the user's spending
_COMPLETED_STATUSES = frozenset(["COMPLETED"])

# Statuses that indicate a terminal failure
_FAILED_STATUSES = frozenset(["FAILED", "PERMANENTLY_FAILED"])

# Source statuses for a completion transition
_PRE_COMPLETION_STATUSES = frozenset([
    "PENDING", "FRAUD_CHECKING", "FRAUD_CLEARED",
    "BALANCE_FINALIZING", "APPROVED",
])


def detect_transition(
        old_image: Optional[dict],
        new_image: dict) -> Optional[str]:
    """
    Returns: 'COMPLETED' | 'FAILED' | 'REVERSED' |
             'NEW_TRANSACTION' | None

    None means no action needed (metadata update, no status change).
    """
    new_status = str(new_image.get("status", "")).upper()
    old_status = str(old_image.get("status", "")).upper() \
        if old_image else ""

    # New item inserted
    if not old_image:
        if new_status in ("PENDING", "FRAUD_CHECKING"):
            return "NEW_TRANSACTION"
        # Item inserted already completed (rare but handle it)
        if new_status in _COMPLETED_STATUSES:
            return "COMPLETED"
        return None

    # Status unchanged — skip
    if new_status == old_status:
        return None

    # Transition to COMPLETED
    if (new_status in _COMPLETED_STATUSES and
            old_status not in _COMPLETED_STATUSES):
        return "COMPLETED"

    # Transition to FAILED
    if (new_status in _FAILED_STATUSES and
            old_status not in _FAILED_STATUSES):
        return "FAILED"

    # Reversal: COMPLETED → REVERSED
    if (new_status == "REVERSED" and
            old_status in _COMPLETED_STATUSES):
        return "REVERSED"

    # All other transitions: no financial aggregation impact
    return None