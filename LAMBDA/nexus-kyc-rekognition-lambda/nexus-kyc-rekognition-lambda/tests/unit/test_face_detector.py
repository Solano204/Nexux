import json
import os
import pytest
from unittest.mock import patch


class TestFaceDetector:
    """Unit tests for rekognition face_detector module."""

    def _load_fixture(self):
        fixture_path = os.path.join(
            os.path.dirname(__file__), "..", "fixtures", "rekognition_face_response.json")
        with open(fixture_path) as f:
            return json.load(f)

    @patch("src.rekognition.face_detector.rekognition_client")
    def test_parse_face_response_detects_one_face(self, mock_client):
        fixture = self._load_fixture()
        from src.rekognition.face_detector import _parse_response as parse_face_result

        result = parse_face_result(fixture)
        assert result["faceCount"] == 1
        assert result["primaryFace"] is not None
        assert result["hasAcceptableQuality"] is True

    @patch("src.rekognition.face_detector.rekognition_client")
    def test_no_faces_returns_zero(self, mock_client):
        from src.rekognition.face_detector import _parse_response as parse_face_result

        result = parse_face_result({"FaceDetails": []})
        assert result["faceCount"] == 0
        assert result["primaryFace"] is None
        assert result["hasAcceptableQuality"] is False

    @patch("src.rekognition.face_detector.rekognition_client")
    def test_high_quality_face_passes(self, mock_client):
        fixture = self._load_fixture()
        from src.rekognition.face_detector import _parse_response as parse_face_result

        result = parse_face_result(fixture)
        quality = result["primaryFace"]["quality"]
        assert quality["brightness"] > 40
        assert quality["sharpness"] > 40
        assert result["primaryFace"]["isFrontalFacing"] is True

    @patch("src.rekognition.face_detector.rekognition_client")
    def test_face_area_calculated(self, mock_client):
        fixture = self._load_fixture()
        from src.rekognition.face_detector import _parse_response as parse_face_result

        result = parse_face_result(fixture)
        assert result["primaryFace"]["faceAreaPercent"] > 0
