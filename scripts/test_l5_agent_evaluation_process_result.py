import importlib.util
import io
import json
import os
import unittest
from pathlib import Path
import sys
from contextlib import redirect_stderr
from tempfile import TemporaryDirectory
from unittest.mock import patch

ROOT = Path(__file__).parents[1]
SPEC = importlib.util.spec_from_file_location(
    "run_l5_agentteams_eval", ROOT / "scripts/run-l5-agentteams-eval.py")
ENTRYPOINT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = ENTRYPOINT
SPEC.loader.exec_module(ENTRYPOINT)


class L5AgentEvaluationProcessResultTest(unittest.TestCase):
    def test_entrypoint_rejects_missing_short_lived_token_before_network_access(self):
        stderr = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), redirect_stderr(stderr):
            result = ENTRYPOINT.main(["--tenant", "tenant-a", "--project", "project-a", "--team", "team-a"])

        self.assertEqual(2, result)
        self.assertIn("AGENTTEAMS_EVAL_TOKEN", stderr.getvalue())
        self.assertNotIn("Bearer", stderr.getvalue())

    def test_rejects_case_scope_that_does_not_match_selected_l5_target(self):
        cases = [{"case_id": "case-a", "task": {"scope": {
            "tenant": "tenant-a", "project": "project-a", "team": "team-a"}}}]

        with self.assertRaises(ENTRYPOINT.EvalError):
            ENTRYPOINT.validate_case_scopes(cases, "tenant-a", "other-project", "team-a")

    def test_requires_project_execution_permission_and_matching_team(self):
        class Client:
            def _request(self, method, path):
                if path == "/api/v1/projects?q=project-a":
                    return {"items": [{"id": "project-id", "tenantId": "tenant-a", "name": "project-a"}]}
                if path == "/api/v1/projects/project-id/role?requiredRole=ADMIN":
                    return {"allowed": False}
                raise AssertionError(path)

        with self.assertRaises(ENTRYPOINT.EvalError):
            ENTRYPOINT.assert_scope_access(Client(), "tenant-a", "project-a", "team-a")

    def test_rejects_team_missing_from_the_selected_project(self):
        class Client:
            def _request(self, method, path):
                if path == "/api/v1/projects?q=project-a":
                    return {"items": [{"id": "project-id", "tenantId": "tenant-a",
                                       "name": "project-a", "status": "ACTIVE"}]}
                if path == "/api/v1/projects/project-id/role?requiredRole=ADMIN":
                    return {"allowed": True}
                if path == "/api/v1/teams?projectId=project-id":
                    return [{"id": "team-id", "name": "other-team", "status": "ACTIVE"}]
                raise AssertionError(path)

        with self.assertRaises(ENTRYPOINT.EvalError):
            ENTRYPOINT.assert_scope_access(Client(), "tenant-a", "project-a", "team-a")

    def test_accepts_matching_project_team_and_admin_role(self):
        class Client:
            def _request(self, method, path):
                if path == "/api/v1/projects?q=project-a":
                    return {"items": [{"id": "project-id", "tenantId": "tenant-a",
                                       "name": "project-a", "status": "ACTIVE"}]}
                if path == "/api/v1/projects/project-id/role?requiredRole=ADMIN":
                    return {"allowed": True}
                if path == "/api/v1/teams?projectId=project-id":
                    return [{"id": "team-id", "name": "team-a", "status": "ACTIVE"}]
                raise AssertionError(path)

        self.assertEqual("project-id", ENTRYPOINT.assert_scope_access(Client(), "tenant-a", "project-a", "team-a"))

    def test_worker_preflight_ignores_historical_and_other_scope_workers(self):
        workers = {"items": [
            {"metadata": {"name": "old", "labels": {"agentteams.io/tenant": "tenant-a",
                "agentteams.io/project": "project-id", "agentteams.io/team": "team-a"}},
                "status": {"phase": "Terminated"}},
            {"metadata": {"name": "other", "labels": {"agentteams.io/tenant": "tenant-b",
                "agentteams.io/project": "other-project", "agentteams.io/team": "team-b"}},
                "status": {"phase": "Ready"}},
            {"metadata": {"name": "target", "labels": {"agentteams.io/tenant": "tenant-a",
                "agentteams.io/project": "project-id", "agentteams.io/team": "team-a"}},
                "status": {"phase": "Ready"}},
        ]}
        with patch.object(ENTRYPOINT, "command", return_value=json.dumps(workers)):
            ENTRYPOINT.assert_workers_ready("agentteams", "tenant-a", "project-id", "team-a")

        workers["items"][2]["status"]["phase"] = "Progressing"
        with patch.object(ENTRYPOINT, "command", return_value=json.dumps(workers)):
            with self.assertRaises(ENTRYPOINT.EvalError):
                ENTRYPOINT.assert_workers_ready("agentteams", "tenant-a", "project-id", "team-a")

    def test_report_rejects_token_and_signed_download_url(self):
        with self.assertRaises(ENTRYPOINT.EvalError):
            ENTRYPOINT.validate_report_safety({"error": "Bearer short-lived-token-value"}, "short-lived-token-value")
        with self.assertRaises(ENTRYPOINT.EvalError):
            ENTRYPOINT.validate_report_safety(
                {"actual": "https://storage.example/file?X-Goog-Signature=secret"}, "short-lived-token-value")

    def test_refuses_to_create_report_file_when_token_would_be_serialized(self):
        with TemporaryDirectory() as temp_dir:
            report_path = Path(temp_dir) / "report.json"
            with self.assertRaises(ENTRYPOINT.EvalError):
                ENTRYPOINT.write_report(report_path, {"token": "short-lived-token-value"}, "short-lived-token-value")
            self.assertFalse(report_path.exists())


if __name__ == "__main__":
    unittest.main()
