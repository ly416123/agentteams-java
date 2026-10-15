#!/usr/bin/env python3
"""Guard the Kind memory scope isolation acceptance contract."""

import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location("run_kind_memory_scope", ROOT / "scripts/run-kind-memory-scope.py")
MEMORY_SCOPE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MEMORY_SCOPE)


class KindMemoryScopeContractTest(unittest.TestCase):
    def test_acceptance_script_exists_and_checks_all_memory_boundaries(self):
        script = (ROOT / "scripts/run-kind-memory-scope.py").read_text(encoding="utf-8")
        for required in (
                "USER_PRIVATE", "PROJECT_SHARED", "TEAM_SHARED", "ORGANIZATION_SHARED",
                "cross-project", "cross-tenant", "subjectId", "/api/v1/memory",
                "KIND_MEMORY_SCOPE_OK"):
            self.assertIn(required, script)

    def test_memory_fixtures_use_the_canonical_project_uuid(self):
        script = (ROOT / "scripts/run-kind-memory-scope.py").read_text(encoding="utf-8")
        self.assertIn("def resolve_project_id(", script)
        self.assertIn("SELECT id::text FROM projects", script)
        self.assertNotIn('"project-a", "team-a"', script)
        expected = "92f86b0b-830d-4fbe-8cc7-a0438f058522"
        with patch.object(MEMORY_SCOPE, "sql", return_value=expected + "\n"):
            resolved = MEMORY_SCOPE.resolve_project_id("agentteams", "postgresql-0", "tenant-a", "project-a")
        self.assertEqual(resolved, expected)

    def test_acceptance_script_requires_oidc_credentials_and_cleans_dev_fixtures(self):
        script = (ROOT / "scripts/run-kind-memory-scope.py").read_text(encoding="utf-8")
        for required in (
                "AGENTTEAMS_E2E_USERNAME", "AGENTTEAMS_E2E_PASSWORD",
                "AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME", "AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD",
                "finally:", "DELETE FROM memories"):
            self.assertIn(required, script)
        self.assertNotIn("apikey", script.lower())
        self.assertNotIn("print(token", script.lower())

    def test_recovery_diagnostics_capture_worker_container_termination_reason(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("worker-container-status.json", workflow)
        self.assertIn(".lastState.terminated", workflow)
        self.assertIn("exitCode,reason,signal", workflow)

    def test_oidc_workflow_runs_memory_scope_acceptance_after_oidc_install(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("Run Kind memory scope isolation acceptance", workflow)
        self.assertLess(
            workflow.index("Install CRDs and local OIDC"),
            workflow.index("Run Kind memory scope isolation acceptance"),
        )


if __name__ == "__main__":
    unittest.main()
