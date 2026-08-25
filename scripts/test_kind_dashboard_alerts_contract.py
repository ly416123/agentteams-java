#!/usr/bin/env python3
"""Guard the Kind Dashboard alert end-to-end acceptance contract."""

from __future__ import annotations

from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]


class KindDashboardAlertsContractTest(unittest.TestCase):
    def test_acceptance_script_and_receiver_assets_exist(self):
        script = ROOT / "scripts/run-kind-dashboard-alerts.py"
        manifest = ROOT / "deploy/kind-dashboard-alert-receiver.yaml"
        receiver = ROOT / "scripts/dashboard-alert-receiver.py"
        self.assertTrue(script.exists())
        self.assertTrue(manifest.exists())
        self.assertTrue(receiver.exists())

        script_text = script.read_text(encoding="utf-8")
        for required in (
                "dashboard_alert_rules", "model_call_audits", "dashboard_alert_events",
                "FAILED", "SENT", "KIND_DASHBOARD_ALERTS_OK", "/api/v1/dashboard/alerts/events"):
            self.assertIn(required, script_text)
        receiver_text = receiver.read_text(encoding="utf-8")
        for required in ("DASHBOARD_ALERT_RECEIVER_MODE", "500", "200"):
            self.assertIn(required, receiver_text)

    def test_recovery_workflow_enables_and_runs_alert_acceptance(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["kind-recovery"]["steps"]
        install = next(step for step in steps if step.get("name") == "Install AgentTeams chart")
        install_run = install["run"]
        self.assertIn("AGENTTEAMS_DASHBOARD_ALERTS_SCHEDULER_ENABLED", install_run)
        self.assertIn("AGENTTEAMS_DASHBOARD_ALERTS_NOTIFICATION_WEBHOOK_URL", install_run)
        alert_step = next(step for step in steps if step.get("name") == "Run Kind Dashboard alert acceptance")
        self.assertIn("scripts/run-kind-dashboard-alerts.py", alert_step["run"])


if __name__ == "__main__":
    unittest.main()
