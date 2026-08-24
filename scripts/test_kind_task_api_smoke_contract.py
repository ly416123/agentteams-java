#!/usr/bin/env python3
"""Guard the Kind Task API smoke against a stale cross-step port-forward."""

from __future__ import annotations

from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]


class KindTaskApiSmokeContractTest(unittest.TestCase):
    def test_smoke_step_owns_and_waits_for_control_plane_port_forward(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
        steps = workflow["jobs"]["kind-recovery"]["steps"]
        smoke_step = next(
            step for step in steps
            if step.get("name") == "Run Kind Task and Artifact API smoke"
        )
        run = smoke_step["run"]
        self.assertIn("kubectl -n agentteams port-forward", run)
        self.assertIn("service/agentteams-agentteams-java-control-plane", run)
        self.assertIn("18081:8080", run)
        self.assertIn("/actuator/health", run)
        self.assertIn("./scripts/smoke-kind-task-api.sh", run)

    def test_smoke_script_handles_empty_auth_args_with_nounset(self):
        smoke = (ROOT / "scripts/smoke-kind-task-api.sh").read_text(encoding="utf-8")
        self.assertIn("curl_api()", smoke)
        self.assertIn("if (( ${#API_AUTH_ARGS[@]} > 0 )); then", smoke)
        self.assertNotIn('curl --fail-with-body --silent --show-error\\\n    -X POST "${BASE_URL}', smoke)


if __name__ == "__main__":
    unittest.main()
