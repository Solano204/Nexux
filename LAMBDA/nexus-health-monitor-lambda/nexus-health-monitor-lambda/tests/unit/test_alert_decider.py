"""Unit tests for alert decider threshold logic."""

import pytest
from src.analysis.alert_decider import should_alert, should_escalate


class TestAlertDecider:

    def test_critical_alerts_on_first_failure(self):
        result = {"criticality": "CRITICAL"}
        assert should_alert(result, 1) is True

    def test_high_does_not_alert_on_first_failure(self):
        result = {"criticality": "HIGH"}
        assert should_alert(result, 1) is False

    def test_high_alerts_on_second_failure(self):
        result = {"criticality": "HIGH"}
        assert should_alert(result, 2) is True

    def test_standard_does_not_alert_on_first_failure(self):
        result = {"criticality": "STANDARD"}
        assert should_alert(result, 1) is False

    def test_standard_alerts_on_second_failure(self):
        result = {"criticality": "STANDARD"}
        assert should_alert(result, 2) is True

    def test_infrastructure_needs_three_failures(self):
        result = {"criticality": "INFRASTRUCTURE"}
        assert should_alert(result, 2) is False
        assert should_alert(result, 3) is True

    def test_escalate_on_high_consecutive_failures(self):
        assert should_escalate(3) is True

    def test_no_escalate_on_low_failures(self):
        assert should_escalate(1) is False
