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
        worker_step = next(step for step in steps if step.get("name") == "Create real AgentScope worker")
        run = worker_step["run"].replace('\\"', '"')

        self.assertIn('AGENTTEAMS_QUOTA_REMOTE_ENABLED: "true"', run)
        self.assertIn('AGENTTEAMS_SCOPE_TENANT: "tenant-a"', run)
        self.assertIn('AGENTTEAMS_SCOPE_PROJECT: "project-a"', run)
        self.assertIn("secretEnv:", run)
        self.assertIn("AGENTTEAMS_GATEWAY_AUTH_TOKEN", run)
        self.assertIn("INSERT INTO agent_sessions", run)
        self.assertIn("'$AGENT_ID'::uuid", run)
        self.assertIn("'$SESSION_TOKEN_HASH'", run)
        self.assertNotIn(":'agent_id'", run)
        self.assertNotIn(":'token_hash'", run)
        self.assertIn("kind-agent-session", run)
        self.assertIn("key: worker-session-token", run)
        self.assertIn("DEEPSEEK_API_KEY", run)
        self.assertIn("create secret generic kind-agentscope-model", run)
        self.assertIn("secret: kind-agentscope-model", run)
        self.assertIn("key: deepseek-api-key", run)
        self.assertNotIn("secret: agentteams-manager", run)

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
        self.assertIn('"role": "EXECUTOR"', script)
        self.assertIn('"teamRole": "EXECUTOR"', script)
        self.assertIn('"allowedRuntimes": ["agentscope"]', script)

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
