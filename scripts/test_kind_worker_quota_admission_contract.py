#!/usr/bin/env python3
"""Guard the Kind workflow's real Worker quota-admission coverage."""

from __future__ import annotations

import ast
from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]


class KindWorkerQuotaAdmissionContractTest(unittest.TestCase):
    def test_real_worker_is_configured_for_remote_project_quota(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["kind-recovery"]["steps"]
        worker_step = next(step for step in steps if step.get("name") == "Create real QwenPaw worker")
        run = worker_step["run"].replace('\\"', '"')

        self.assertIn('AGENTTEAMS_QUOTA_REMOTE_ENABLED: "true"', run)
        self.assertIn('AGENTTEAMS_SCOPE_TENANT: "tenant-a"', run)
        self.assertIn('AGENTTEAMS_SCOPE_PROJECT: "project-a"', run)

    def test_recovery_job_runs_real_worker_quota_admission(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["kind-recovery"]["steps"]
        admission_step = next(
            step for step in steps if step.get("name") == "Run Kind Worker quota admission"
        )

        self.assertIn("scripts/run-kind-worker-quota-admission.py", admission_step["run"])
        self.assertIn("AGENTTEAMS_AGENT_ID", admission_step.get("env", {}))

        script = (ROOT / "scripts/run-kind-worker-quota-admission.py").read_text(encoding="utf-8")
        self.assertIn("/api/v1/teams", script)
        self.assertIn('f"kind-worker-quota-team-{uuid.uuid4()}"', script)
        self.assertIn('"teamId"', script)
        self.assertIn("/members", script)

        tree = ast.parse(script)
        member_calls = [
            node for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name)
            and node.func.id == "api_request"
            and node.args
            and isinstance(node.args[0], ast.JoinedStr)
            and any(
                isinstance(value, ast.Constant) and "/members" in value.value
                for value in node.args[0].values
            )
        ]
        self.assertEqual(1, len(member_calls))
        self.assertGreaterEqual(len(member_calls[0].args), 4)
        self.assertIsInstance(member_calls[0].args[3], ast.JoinedStr)


if __name__ == "__main__":
    unittest.main()
