# src/checks/response_parser.py
"""
Parse Spring Boot Actuator /actuator/health response.

Actuator response format:
  {
    "status": "UP" | "DOWN" | "OUT_OF_SERVICE" | "UNKNOWN",
    "components": {
      "db":          {"status": "UP", "details": {...}},
      "redis":       {"status": "UP"},
      "kafka":       {"status": "DOWN", "details": {"error": "..."}}
    }
  }

Status normalization:
  UP but slow (> max_response_ms) → DEGRADED
  UP but expected component missing → DEGRADED
  DOWN or OUT_OF_SERVICE → DOWN (identify which components)
  UNKNOWN → UNKNOWN

DEGRADED is important: a service that is "up" but responding in
4000ms when max is 2000ms is degrading and deserves attention
BEFORE it fails completely.
"""

from checks.health_checker import _build_result


def parse_health_response(service: dict,
                          health_data: dict,
                          response_ms: int) -> dict:
    overall = health_data.get("status", "UNKNOWN").upper()
    raw_components = health_data.get("components", {})
    max_ms = service.get("max_response_ms", 3000)

    # Parse each component
    parsed: dict[str, dict] = {}
    for comp_name, comp_data in raw_components.items():
        comp_status = comp_data.get("status", "UNKNOWN").upper()
        comp_details = comp_data.get("details", {})
        parsed[comp_name] = {
            "status": comp_status,
            "isDown": comp_status in ("DOWN", "OUT_OF_SERVICE"),
        }
        if "error" in comp_details:
            parsed[comp_name]["error"] = str(
                comp_details["error"])[:300]

    # Determine normalized status + detail
    if overall == "UP":
        # Check response time degradation
        if response_ms > max_ms:
            return _build_result(service, "DEGRADED",
                                 f"SLOW_RESPONSE:{response_ms}ms_threshold:{max_ms}ms",
                                 response_ms, parsed)

        # Check for missing expected components
        expected = service.get("expected_components", [])
        missing = [c for c in expected if c not in parsed]
        if missing:
            return _build_result(service, "DEGRADED",
                                 f"MISSING_COMPONENTS:{','.join(missing)}",
                                 response_ms, parsed)

        return _build_result(service, "UP", "HEALTHY",
                             response_ms, parsed)

    elif overall in ("DOWN", "OUT_OF_SERVICE"):
        down_comps = [n for n, d in parsed.items() if d["isDown"]]
        detail = (f"COMPONENTS_DOWN:{','.join(down_comps)}"
                  if down_comps else "SERVICE_DOWN")
        return _build_result(service, "DOWN", detail,
                             response_ms, parsed)

    else:
        return _build_result(service, "UNKNOWN",
                             f"UNKNOWN_STATUS:{overall}", response_ms, parsed)