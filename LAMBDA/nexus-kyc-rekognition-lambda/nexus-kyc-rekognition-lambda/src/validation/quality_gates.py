# src/validation/quality_gates.py
"""
Quality gate evaluation — runs after Rekognition completes.

Gates run in order; all failures are collected (not short-circuit).
This ensures the user gets ALL actionable feedback in one attempt
rather than fixing one issue only to discover another.

User guidance is in Spanish (Mexican market) because this is
the user-facing rejection message that the Notification Service
will deliver to the user's phone.

Quality tiers:
  ACCEPTABLE  — all gates passed, proceed to AI vision model
  MARGINAL    — only soft gates failed (low confidence, low sharpness)
                 still sent to AI model with lower expected accuracy
  REJECTED    — hard gates failed (no face, no text, wrong document type)
                 user must resubmit with corrected photo
"""

import os
from typing import Optional

MIN_TEXT_COUNT = int(os.environ.get("MIN_TEXT_ELEMENT_COUNT", "5"))
MIN_TEXT_CONFIDENCE = float(os.environ.get("MIN_TEXT_CONFIDENCE", "70"))

# Soft gates: failure is a warning, not a hard rejection
_SOFT_GATE_REASONS = frozenset([
    "LOW_TEXT_CONFIDENCE",
    "FACE_QUALITY_BELOW_THRESHOLD",
    "FACE_TOO_DARK",
    "FACE_BLURRY",
])

# User-facing guidance (Spanish)
_USER_GUIDANCE: dict[str, str] = {
    "INSUFFICIENT_TEXT_DETECTED": (
        "La imagen del documento no muestra suficiente texto legible. "
        "Asegúrese de fotografiar el frente completo de su identificación "
        "con buena iluminación y sin reflejos."
    ),
    "LOW_TEXT_CONFIDENCE": (
        "El texto en la imagen no está suficientemente nítido. "
        "Enfoque la cámara directamente sobre el documento y "
        "evite el movimiento al tomar la foto."
    ),
    "NO_FACE_DETECTED": (
        "No se detectó ninguna foto en el documento. "
        "Por favor fotografíe el frente de su identificación, "
        "el lado que contiene su fotografía."
    ),
    "FACE_TOO_DARK": (
        "La foto en la identificación aparece muy oscura. "
        "Tome la foto en un área bien iluminada."
    ),
    "FACE_BLURRY": (
        "La foto en la identificación está borrosa. "
        "Asegúrese de que la cámara esté bien enfocada."
    ),
    "FACE_TURNED_SIDEWAYS": (
        "El documento parece estar girado. "
        "Coloque la identificación de frente a la cámara."
    ),
    "FACE_TILTED_VERTICAL": (
        "El documento está inclinado. "
        "Sostenga la cámara paralela al documento."
    ),
    "FACE_LOW_CONFIDENCE": (
        "La foto en el documento no fue reconocida con suficiente "
        "claridad. Intente con mejor iluminación."
    ),
}


def evaluate_quality_gates(
        text_result: Optional[dict],
        face_result: Optional[dict],
        text_error: Optional[str],
        face_error: Optional[str]) -> dict:
    """
    Evaluate all quality gates. Returns full assessment dict.
    """
    gates: list[dict] = []
    failed_reasons: list[str] = []

    # ── Text detection gates ───────────────────────────────
    if text_error:
        gates.append(_gate("TEXT_DETECTION_SERVICE",
                           passed=False,
                           reason="TEXT_DETECTION_SERVICE_ERROR",
                           detail=text_error,
                           guidance=None))
        failed_reasons.append("TEXT_DETECTION_SERVICE_ERROR")
    else:
        # Gate: minimum line count
        line_count = (text_result or {}).get("lineCount", 0)
        text_count_ok = line_count >= MIN_TEXT_COUNT
        gates.append(_gate(
            "MINIMUM_TEXT_COUNT",
            passed=text_count_ok,
            reason="INSUFFICIENT_TEXT_DETECTED",
            detail=f"detected={line_count} required={MIN_TEXT_COUNT}",
            guidance=_USER_GUIDANCE["INSUFFICIENT_TEXT_DETECTED"]
            if not text_count_ok else None,
        ))
        if not text_count_ok:
            failed_reasons.append("INSUFFICIENT_TEXT_DETECTED")

        # Gate: average text confidence
        avg_conf = (text_result or {}).get("averageConfidence", 0.0)
        confidence_ok = avg_conf >= MIN_TEXT_CONFIDENCE
        gates.append(_gate(
            "TEXT_CONFIDENCE",
            passed=confidence_ok,
            reason="LOW_TEXT_CONFIDENCE",
            detail=f"avg={avg_conf:.1f} required={MIN_TEXT_CONFIDENCE}",
            guidance=_USER_GUIDANCE["LOW_TEXT_CONFIDENCE"]
            if not confidence_ok else None,
        ))
        if not confidence_ok:
            failed_reasons.append("LOW_TEXT_CONFIDENCE")

    # ── Face detection gates ───────────────────────────────
    if face_error:
        gates.append(_gate("FACE_DETECTION_SERVICE",
                           passed=False,
                           reason="FACE_DETECTION_SERVICE_ERROR",
                           detail=face_error,
                           guidance=None))
        failed_reasons.append("FACE_DETECTION_SERVICE_ERROR")
    else:
        face_count = (face_result or {}).get("faceCount", 0)

        # Gate: face presence
        face_present_ok = face_count >= 1
        gates.append(_gate(
            "FACE_PRESENCE",
            passed=face_present_ok,
            reason="NO_FACE_DETECTED",
            detail=f"detected={face_count} required=1",
            guidance=_USER_GUIDANCE["NO_FACE_DETECTED"]
            if not face_present_ok else None,
        ))
        if not face_present_ok:
            failed_reasons.append("NO_FACE_DETECTED")

        # Gate: face quality (only if face was found)
        if face_count >= 1:
            face_ok = (face_result or {}).get(
                "hasAcceptableQuality", False)
            face_reject = (face_result or {}).get(
                "qualityRejectReasons", [])
            primary_reason = (
                face_reject[0] if face_reject
                else "FACE_QUALITY_BELOW_THRESHOLD"
            )
            gates.append(_gate(
                "FACE_QUALITY",
                passed=face_ok,
                reason=primary_reason,
                detail=f"rejectReasons={face_reject}",
                guidance=_lookup_face_guidance(face_reject)
                if not face_ok else None,
            ))
            if not face_ok:
                failed_reasons.extend(face_reject)

    # ── Determine overall quality tier ─────────────────────
    if not failed_reasons:
        tier = "ACCEPTABLE"
    elif all(r in _SOFT_GATE_REASONS for r in failed_reasons):
        tier = "MARGINAL"
    else:
        tier = "REJECTED"

    can_proceed = tier in ("ACCEPTABLE", "MARGINAL")

    return {
        "passedAllGates": len(failed_reasons) == 0,
        "qualityTier": tier,
        "canProceedToAiModel": can_proceed,
        "gates": gates,
        "failedGates": failed_reasons,
        "userFacingRejectReasons": [
            g["userGuidance"] for g in gates
            if not g["passed"] and g.get("userGuidance")
        ],
    }


def _gate(name: str, passed: bool, reason: str,
          detail: Optional[str],
          guidance: Optional[str]) -> dict:
    result: dict = {"gate": name, "passed": passed}
    if not passed:
        result["reason"] = reason
        if detail:
            result["detail"] = detail
        if guidance:
            result["userGuidance"] = guidance
    return result


def _lookup_face_guidance(reject_reasons: list[str]) -> str:
    for reason in reject_reasons:
        if reason in _USER_GUIDANCE:
            return _USER_GUIDANCE[reason]
    return (
        "La calidad del documento no cumple los requisitos mínimos. "
        "Por favor tome una nueva foto con mejor iluminación y nitidez."
    )