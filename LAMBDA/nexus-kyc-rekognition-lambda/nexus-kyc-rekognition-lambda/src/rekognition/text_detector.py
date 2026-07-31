# src/rekognition/text_detector.py
"""
AWS Rekognition DetectText wrapper.

Key design decisions:
- Uses S3Object reference: Rekognition reads from S3 directly,
  the Lambda never downloads image bytes.
- Filters by MIN_TEXT_CONFIDENCE: noisy low-confidence detections
  are excluded before pattern analysis.
- Provides consolidatedText for direct injection into GPT-4V prompt.
- detectedDocumentTypeHint saves the vision model from guessing
  the document type when OCR already reveals it.
"""

import os
import re
from typing import Optional

import boto3

from src.utils.logging_config import log

rekognition_client = boto3.client("rekognition")

MIN_CONFIDENCE = float(os.environ.get("MIN_TEXT_CONFIDENCE", "70"))

# Mexican document keywords for hint detection
_MEXICO_ID_KEYWORDS = frozenset([
    "INSTITUTO NACIONAL ELECTORAL", "INE", "IFE",
    "CREDENCIAL", "CURP", "RFC",
])
_PASSPORT_KEYWORDS = frozenset([
    "PASAPORTE", "PASSPORT", "MRPMEXICO",
])
_LICENSE_KEYWORDS = frozenset([
    "LICENCIA", "CONDUCIR", "DRIVER", "LICENSE",
])
_PROFESSIONAL_KEYWORDS = frozenset([
    "CEDULA", "PROFESIONAL", "CÉDULA",
])

# Matches alphanumeric sequences 8-20 chars (potential ID numbers) -
# requires at least one digit, or plain uppercase Spanish document-header
# words (INSTITUTO, NACIONAL, ELECTORAL, CREDENCIAL...) match just as
# readily as real IDs and crowd them out of the [:5] slice in _parse_response.
_ID_NUMBER_RE = re.compile(r"\b(?=[A-Z0-9]*\d)[A-Z0-9]{8,20}\b")


def detect_text(bucket: str, key: str) -> Optional[dict]:
    """
    Call Rekognition DetectText and return structured result.
    Returns None on API error (caller handles gracefully).
    """
    try:
        response = rekognition_client.detect_text(
            Image={"S3Object": {"Bucket": bucket, "Name": key}}
        )
        return _parse_response(response)
    except Exception as exc:
        log.error("Rekognition detect_text failed",
                  bucket=bucket, key=key, error=str(exc))
        return None


def _parse_response(response: dict) -> dict:
    all_detections = response.get("TextDetections", [])

    # Separate high-confidence LINEs and WORDs
    lines = [
        t for t in all_detections
        if t["Type"] == "LINE"
           and t["Confidence"] >= MIN_CONFIDENCE
    ]
    words = [
        t for t in all_detections
        if t["Type"] == "WORD"
           and t["Confidence"] >= MIN_CONFIDENCE
    ]

    # Consolidated text — injected verbatim into GPT-4V system prompt
    consolidated = " ".join(ln["DetectedText"] for ln in lines)
    upper = consolidated.upper()

    # Structured text elements with spatial data
    text_elements = [
        {
            "text": ln["DetectedText"],
            "confidence": round(ln["Confidence"], 2),
            "boundingBox": _bbox(ln),
            "type": "LINE",
        }
        for ln in lines
    ]

    avg_confidence = (
        sum(t["Confidence"] for t in all_detections)
        / len(all_detections)
        if all_detections
        else 0.0
    )

    id_numbers = _ID_NUMBER_RE.findall(consolidated)

    return {
        "lineCount": len(lines),
        "wordCount": len(words),
        "totalDetectedElements": len(all_detections),
        "averageConfidence": round(avg_confidence, 2),
        "consolidatedText": consolidated,
        "textElements": text_elements,
        "hasDatePattern": _has_date_pattern(lines),
        "hasMexicoIdKeywords": bool(
            _MEXICO_ID_KEYWORDS & _tokens(upper)),
        "potentialIdNumbers": id_numbers[:5],
        "detectedDocumentTypeHint": _document_type_hint(upper),
    }


def _has_date_pattern(lines: list[dict]) -> bool:
    """True if any line looks like a date (digits + separators)."""
    for ln in lines:
        text = ln["DetectedText"]
        has_digits = any(c.isdigit() for c in text)
        has_sep = any(sep in text for sep in ("/", "-", " "))
        if has_digits and has_sep:
            return True
    return False


def _tokens(upper_text: str) -> frozenset[str]:
    """Return set of uppercase word-tokens from text."""
    return frozenset(upper_text.split())


def _document_type_hint(upper: str) -> str:
    if _PASSPORT_KEYWORDS & _tokens(upper):
        return "PASSPORT"
    if _MEXICO_ID_KEYWORDS & _tokens(upper):
        return "MEXICO_NATIONAL_ID"
    if _LICENSE_KEYWORDS & _tokens(upper):
        return "DRIVERS_LICENSE"
    if _PROFESSIONAL_KEYWORDS & _tokens(upper):
        return "PROFESSIONAL_ID"
    return "UNKNOWN"


def _bbox(detection: dict) -> dict:
    bb = detection["Geometry"]["BoundingBox"]
    return {
        "left": round(bb["Left"], 4),
        "top": round(bb["Top"], 4),
        "width": round(bb["Width"], 4),
        "height": round(bb["Height"], 4),
    }