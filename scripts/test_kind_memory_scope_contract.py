#!/usr/bin/env python3
"""Guard the Kind memory scope isolation acceptance contract."""

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class KindMemoryScopeContractTest(unittest.TestCase):
    def test_acceptance_script_exists_and_checks_all_memory_boundaries(self):
        script = (ROOT / "scripts/run-kind-memory-scope.py").read_text(encoding="utf-8")
        for required in (
                "USER_PRIVATE", "PROJECT_SHARED", "TEAM_SHARED", "ORGANIZATION_SHARED",
                "cross-project", "cross-tenant", "subjectId", "/api/v1/memory",
                "KIND_MEMORY_SCOPE_OK"):
            self.assertIn(required, script)

    def test_acceptance_script_requires_oidc_credentials_and_cleans_dev_fixtures(self):
        script = (ROOT / "scripts/run-kind-memory-scope.py").read_text(encoding="utf-8")
        for required in (
                "AGENTTEAMS_E2E_USERNAME", "AGENTTEAMS_E2E_PASSWORD",
                "AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME", "AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD",
                "finally:", "DELETE FROM memories"):
            self.assertIn(required, script)
        self.assertNotIn("apikey", script.lower())
        self.assertNotIn("print(token", script.lower())

    def test_oidc_workflow_runs_memory_scope_acceptance_after_oidc_install(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("Run Kind memory scope isolation acceptance", workflow)
        self.assertLess(
            workflow.index("Install CRDs and local OIDC"),
            workflow.index("Run Kind memory scope isolation acceptance"),
        )


if __name__ == "__main__":
    unittest.main()
