import pytest
from unittest.mock import patch


class TestQualityGates:
    """Unit tests for quality_gates.evaluate_quality_gates()."""

    def _build_passing_rekognition_result(self):
        return {
            "textDetection": {
                "lineCount": 10,
                "averageConfidence": 95.0,
                "consolidatedText": "INE MEXICO NOMBRE TEST",
            },
            "faceDetection": {
                "faceCount": 1,
                "primaryFace": {
                    "confidence": 99.5,
                    "quality": {"brightness": 75.0, "sharpness": 85.0},
                    "pose": {"yaw": 2.0, "pitch": -1.0, "roll": 0.5},
                },
                "hasAcceptableQuality": True,
                "qualityRejectReasons": [],
            },
            "textError": None,
            "faceError": None,
            "textCount": 10,
            "faceDetected": True,
        }

    def test_all_gates_pass_for_good_document(self):
        from validation.quality_gates import evaluate_quality_gates

        result = evaluate_quality_gates(self._build_passing_rekognition_result())
        assert result["passedAllGates"] is True
        assert result["qualityTier"] == "ACCEPTABLE"
        assert result["canProceedToAiModel"] is True

    def test_no_face_fails_gate(self):
        from validation.quality_gates import evaluate_quality_gates

        data = self._build_passing_rekognition_result()
        data["faceDetection"]["faceCount"] = 0
        data["faceDetected"] = False

        result = evaluate_quality_gates(data)
        assert result["passedAllGates"] is False
        assert "NO_FACE_DETECTED" in result["failedGates"]

    def test_insufficient_text_fails_gate(self):
        from validation.quality_gates import evaluate_quality_gates

        data = self._build_passing_rekognition_result()
        data["textDetection"]["lineCount"] = 2
        data["textCount"] = 2

        result = evaluate_quality_gates(data)
        assert result["passedAllGates"] is False
        assert "INSUFFICIENT_TEXT_DETECTED" in result["failedGates"]

    def test_marginal_quality_can_proceed(self):
        from validation.quality_gates import evaluate_quality_gates

        data = self._build_passing_rekognition_result()
        data["textDetection"]["averageConfidence"] = 55.0

        result = evaluate_quality_gates(data)
        assert result["qualityTier"] == "MARGINAL"
        assert result["canProceedToAiModel"] is True

    def test_rekognition_error_sets_service_error(self):
        from validation.quality_gates import evaluate_quality_gates

        data = self._build_passing_rekognition_result()
        data["textError"] = "ServiceUnavailableException"

        result = evaluate_quality_gates(data)
        assert "TEXT_DETECTION_SERVICE_ERROR" in result["failedGates"]

    def test_user_guidance_in_spanish(self):
        from validation.quality_gates import evaluate_quality_gates

        data = self._build_passing_rekognition_result()
        data["faceDetection"]["faceCount"] = 0
        data["faceDetected"] = False

        result = evaluate_quality_gates(data)
        user_reasons = result["userFacingRejectReasons"]
        assert len(user_reasons) > 0
        assert any("foto" in r.lower() or "documento" in r.lower() for r in user_reasons)
