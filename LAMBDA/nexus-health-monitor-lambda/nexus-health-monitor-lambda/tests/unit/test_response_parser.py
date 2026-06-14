"""Unit tests for Spring Boot Actuator response parsing."""

import pytest
from src.checks.response_parser import parse_health_response


def _svc(max_ms: int = 2000, expected: list = None) -> dict:
    return {
        "name": "test-service",
        "port": 8080,
        "health_path": "/actuator/health",
        "criticality": "STANDARD",
        "description": "test",
        "expected_components": expected or [],
        "max_response_ms": max_ms,
        "blocks_transactions": False,
        "saga_participant": False,
    }


class TestResponseParser:

    def test_up_fast_response_is_healthy(self):
        data = {"status": "UP",
                 "components": {"db": {"status": "UP"}}}
        result = parse_health_response(_svc(), data, 500)
        assert result["status"] == "UP"
        assert result["statusDetail"] == "HEALTHY"

    def test_up_slow_response_is_degraded(self):
        data = {"status": "UP",
                 "components": {"db": {"status": "UP"}}}
        result = parse_health_response(_svc(max_ms=1000), data, 1500)
        assert result["status"] == "DEGRADED"
        assert "SLOW_RESPONSE" in result["statusDetail"]
        assert "1500ms" in result["statusDetail"]

    def test_down_status_is_down(self):
        data = {
            "status": "DOWN",
            "components": {
                "db": {"status": "DOWN",
                       "details": {"error": "Connection refused"}}
            }
        }
        result = parse_health_response(_svc(), data, 200)
        assert result["status"] == "DOWN"
        assert "db" in result["statusDetail"]

    def test_http_503_with_down_components_parsed(self):
        data = {
            "status": "DOWN",
            "components": {
                "kafka": {"status": "DOWN"},
                "redis": {"status": "UP"},
            }
        }
        result = parse_health_response(_svc(), data, 100)
        assert result["status"] == "DOWN"
        assert result["components"]["kafka"]["isDown"] is True
        assert result["components"]["redis"]["isDown"] is False

    def test_missing_expected_component_is_degraded(self):
        # Service expects 'kafka' but response has no kafka component
        data = {
            "status": "UP",
            "components": {"db": {"status": "UP"}},
        }
        result = parse_health_response(
            _svc(expected=["db", "kafka"]), data, 200)
        assert result["status"] == "DEGRADED"
        assert "MISSING_COMPONENTS" in result["statusDetail"]
        assert "kafka" in result["statusDetail"]

    def test_all_expected_components_present_is_up(self):
        data = {
            "status": "UP",
            "components": {
                "db": {"status": "UP"},
                "kafka": {"status": "UP"},
            }
        }
        result = parse_health_response(
            _svc(expected=["db", "kafka"]), data, 200)
        assert result["status"] == "UP"

    def test_component_error_captured(self):
        data = {
            "status": "DOWN",
            "components": {
                "db": {
                    "status": "DOWN",
                    "details": {"error": "timeout after 30s"}
                }
            }
        }
        result = parse_health_response(_svc(), data, 100)
        assert result["components"]["db"]["error"] == \
            "timeout after 30s"

    def test_out_of_service_treated_as_down(self):
        data = {"status": "OUT_OF_SERVICE", "components": {}}
        result = parse_health_response(_svc(), data, 100)
        assert result["status"] == "DOWN"
