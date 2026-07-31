# src/rekognition/face_detector.py
"""
AWS Rekognition DetectFaces wrapper.

For KYC documents, exactly one face is expected.
Face quality thresholds are configurable via environment variables.

Quality gates:
  Brightness: >= 40 (0=black, 100=white)
  Sharpness:  >= 40 (0=blurry, 100=sharp)
  Confidence: >= 80 (certainty this is a face)
  Frontal:    yaw < 30° and pitch < 30°

Pose thresholds matter for ID documents:
  - Extreme yaw (turned sideways): photo is of document edge
  - Extreme pitch (tilted up/down): document is angled away
Both indicate poor photograph technique, not bad document.
"""

import os
from typing import Optional

import boto3

from src.utils.logging_config import log

rekognition_client = boto3.client("rekognition")

MIN_BRIGHTNESS = float(os.environ.get("MIN_FACE_QUALITY_BRIGHTNESS", "40"))
MIN_SHARPNESS = float(os.environ.get("MIN_FACE_QUALITY_SHARPNESS", "40"))
MIN_FACE_CONFIDENCE = 80.0
MAX_POSE_YAW = 30.0
MAX_POSE_PITCH = 30.0


def detect_faces(bucket: str, key: str) -> Optional[dict]:
    """
    Call Rekognition DetectFaces (Attributes=['ALL']) and return
    structured result. Returns None on API error.
    """
    try:
        response = rekognition_client.detect_faces(
            Image={"S3Object": {"Bucket": bucket, "Name": key}},
            Attributes=["ALL"],
        )
        return _parse_response(response)
    except Exception as exc:
        log.error("Rekognition detect_faces failed",
                  bucket=bucket, key=key, error=str(exc))
        return None


def _parse_response(response: dict) -> dict:
    face_details = response.get("FaceDetails", [])

    if not face_details:
        return {
            "faceCount": 0,
            "primaryFace": None,
            "hasAcceptableQuality": False,
            "qualityRejectReasons": ["NO_FACE_DETECTED"],
        }

    # Multiple faces on an ID is unexpected; use highest-confidence one
    primary = max(face_details, key=lambda f: f.get("Confidence", 0))

    quality = primary.get("Quality", {})
    pose = primary.get("Pose", {})
    bbox = primary.get("BoundingBox", {})

    brightness = quality.get("Brightness", 0.0)
    sharpness = quality.get("Sharpness", 0.0)
    confidence = primary.get("Confidence", 0.0)
    yaw = abs(pose.get("Yaw", 0.0))
    pitch = abs(pose.get("Pitch", 0.0))
    is_frontal = yaw < MAX_POSE_YAW and pitch < MAX_POSE_PITCH

    reject_reasons = _build_reject_reasons(
        brightness, sharpness, confidence, is_frontal, yaw, pitch)
    has_acceptable = len(reject_reasons) == 0

    face_area_pct = round(
        bbox.get("Width", 0) * bbox.get("Height", 0) * 100, 2)

    return {
        "faceCount": len(face_details),
        "primaryFace": {
            "confidence": round(confidence, 2),
            "quality": {
                "brightness": round(brightness, 2),
                "sharpness": round(sharpness, 2),
            },
            "pose": {
                "roll": round(pose.get("Roll", 0), 2),
                "yaw": round(yaw, 2),
                "pitch": round(pitch, 2),
            },
            "boundingBox": {
                "left": round(bbox.get("Left", 0), 4),
                "top": round(bbox.get("Top", 0), 4),
                "width": round(bbox.get("Width", 0), 4),
                "height": round(bbox.get("Height", 0), 4),
            },
            "faceAreaPercent": face_area_pct,
            "isFrontalFacing": is_frontal,
            "ageRange": primary.get("AgeRange", {}),
            # Sunglasses and eyes-open: useful compliance signals
            "sunglassesConfidence": primary.get(
                "Sunglasses", {}).get("Confidence", 0),
            "eyesOpenConfidence": primary.get(
                "EyesOpen", {}).get("Confidence", 0),
        },
        "hasAcceptableQuality": has_acceptable,
        "qualityRejectReasons": reject_reasons,
    }


def _build_reject_reasons(
        brightness: float, sharpness: float,
        confidence: float, is_frontal: bool,
        yaw: float, pitch: float) -> list[str]:
    reasons: list[str] = []
    if brightness < MIN_BRIGHTNESS:
        reasons.append("FACE_TOO_DARK")
    if sharpness < MIN_SHARPNESS:
        reasons.append("FACE_BLURRY")
    if confidence < MIN_FACE_CONFIDENCE:
        reasons.append("FACE_LOW_CONFIDENCE")
    if not is_frontal:
        if yaw >= MAX_POSE_YAW:
            reasons.append("FACE_TURNED_SIDEWAYS")
        if pitch >= MAX_POSE_PITCH:
            reasons.append("FACE_TILTED_VERTICAL")
    return reasons