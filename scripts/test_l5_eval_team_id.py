import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[1]
SPEC = importlib.util.spec_from_file_location(
    "run_l5_agentteams_eval", ROOT / "scripts/run-l5-agentteams-eval.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class FakeClient:
    def __init__(self):
        self.calls = []

    def _request(self, method, path):
        self.calls.append((method, path))
        if path == "/api/v1/projects?q=project":
            return {"items": [{"id": "project-uuid", "name": "project",
                               "tenantId": "tenant", "status": "ACTIVE"}]}
        if path == "/api/v1/projects/project-uuid/role?requiredRole=ADMIN":
            return {"allowed": True}
        if path == "/api/v1/teams/team-uuid?projectId=project-uuid":
            return {"id": "team-uuid", "name": "isolated-team", "status": "ACTIVE"}
        raise AssertionError(f"unexpected request: {method} {path}")


class L5EvalTeamIdTest(unittest.TestCase):
    def test_explicit_team_id_selects_isolated_team_within_project_scope(self):
        client = FakeClient()
        result = RUNNER.assert_scope_access(client, "tenant", "project", "team-a", "team-uuid")
        self.assertEqual(result, ("project-uuid", "team-uuid"))
        self.assertIn(("GET", "/api/v1/teams/team-uuid?projectId=project-uuid"), client.calls)


if __name__ == "__main__":
    unittest.main()
