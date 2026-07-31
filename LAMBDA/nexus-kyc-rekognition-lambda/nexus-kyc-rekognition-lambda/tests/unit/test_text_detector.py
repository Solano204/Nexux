import json
import os
import pytest
from unittest.mock import patch, MagicMock


class TestTextDetector:
    """Unit tests for rekognition text_detector module."""

    def _load_fixture(self):
        fixture_path = os.path.join(
            os.path.dirname(__file__), "..", "fixtures", "rekognition_text_response.json")
        with open(fixture_path) as f:
            return json.load(f)

    @patch("src.rekognition.text_detector.rekognition_client")
    def test_parse_text_response_extracts_lines(self, mock_client):
        """Should extract LINE-type detections with confidence >= threshold."""
        fixture = self._load_fixture()
        from src.rekognition.text_detector import _parse_response as parse_text_result

        result = parse_text_result(fixture)
        assert result["lineCount"] >= 5
        assert result["consolidatedText"] != ""
        assert result["hasMexicoIdKeywords"] is True
        assert result["detectedDocumentTypeHint"] == "MEXICO_NATIONAL_ID"

    @patch("src.rekognition.text_detector.rekognition_client")
    def test_empty_response_returns_zero_counts(self, mock_client):
        from src.rekognition.text_detector import _parse_response as parse_text_result

        result = parse_text_result({"TextDetections": []})
        assert result["lineCount"] == 0
        assert result["wordCount"] == 0
        assert result["consolidatedText"] == ""

    @patch("src.rekognition.text_detector.rekognition_client")
    def test_curp_detected_as_id_number(self, mock_client):
        fixture = self._load_fixture()
        from src.rekognition.text_detector import _parse_response as parse_text_result

        result = parse_text_result(fixture)
        assert any("GALJ900315" in pid for pid in result.get("potentialIdNumbers", []))
