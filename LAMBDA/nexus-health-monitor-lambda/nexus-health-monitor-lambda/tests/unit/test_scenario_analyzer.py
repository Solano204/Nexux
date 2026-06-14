"""Unit tests for platform-wide scenario analysis."""

import pytest
from src.analysis.scenario_analyzer import analyze


class TestScenarioAnalyzer:

    def test_no_scenarios_when_all_up(self):
        results = [
            {"name": svc, "status": "UP",
             "criticality": "CRITICAL",
             "blocks_transactions": True,
             "sagaParticipant": False}
            for svc in ["nexus-api-gateway",
                         "nexus-identity-service",
                         "nexus-account-service",
                         "nexus-transaction-service",
                         "nexus-saga-orchestrator"]
        ]
        scenarios = analyze(results)
        assert len(scenarios) == 0

    def test_cascade_failure_when_majority_down(self):
        results = [
            {"name": f"svc-{i}", "status": "DOWN",
             "criticality": "STANDARD",
             "blocks_transactions": False,
             "sagaParticipant": False}
            for i in range(10)
        ] + [
            {"name": f"svc-up-{i}", "status": "UP",
             "criticality": "STANDARD",
             "blocks_transactions": False,
             "sagaParticipant": False}
            for i in range(2)
        ]
        scenarios = analyze(results)
        types = [s["scenarioType"] for s in scenarios]
        assert "CASCADE_FAILURE" in types

    def test_infra_down_scenario(self):
        results = [
            {"name": "nexus-config-service",
             "status": "DOWN",
             "criticality": "INFRASTRUCTURE",
             "blocks_transactions": False,
             "sagaParticipant": False},
        ]
        scenarios = analyze(results)
        types = [s["scenarioType"] for s in scenarios]
        assert "INFRASTRUCTURE_SERVICES_DOWN" in types

    def test_less_than_50_pct_down_no_cascade(self):
        results = (
            [{"name": f"svc-down-{i}", "status": "DOWN",
              "criticality": "STANDARD",
              "blocks_transactions": False,
              "sagaParticipant": False}
             for i in range(3)]
            + [{"name": f"svc-up-{i}", "status": "UP",
                "criticality": "STANDARD",
                "blocks_transactions": False,
                "sagaParticipant": False}
               for i in range(7)]
        )
        scenarios = analyze(results)
        cascade = [s for s in scenarios
                   if s["scenarioType"] == "CASCADE_FAILURE"]
        assert len(cascade) == 0

    def test_cascade_requires_minimum_5_down(self):
        # 4 down out of 6 = 66% but < 5 services
        results = (
            [{"name": f"svc-down-{i}", "status": "DOWN",
              "criticality": "STANDARD",
              "blocks_transactions": False,
              "sagaParticipant": False}
             for i in range(4)]
            + [{"name": f"svc-up-{i}", "status": "UP",
                "criticality": "STANDARD",
                "blocks_transactions": False,
                "sagaParticipant": False}
               for i in range(2)]
        )
        scenarios = analyze(results)
        cascade = [s for s in scenarios
                   if s["scenarioType"] == "CASCADE_FAILURE"]
        assert len(cascade) == 0

    def test_empty_results_no_scenarios(self):
        scenarios = analyze([])
        assert len(scenarios) == 0
