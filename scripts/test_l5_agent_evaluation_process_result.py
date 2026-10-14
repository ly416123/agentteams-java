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

        self.assertEqual(("project-id", "team-id"),
                         ENTRYPOINT.assert_scope_access(Client(), "tenant-a", "project-a", "team-a"))

    def test_binds_verified_team_id_without_mutating_suite_case(self):
        from scripts.agentteams_eval import EvalCase

        case = EvalCase("case-a", "case-a", {"scope": {"tenant": "tenant-a", "project": "project-a",
            "team": "team-a"}, "taskType": "agentscope", "inputJson": {"prompt": "work"},
            "requiredCapabilities": ["runtime-agentscope-v1"]}, [], {})
        bound = ENTRYPOINT.bind_team_id([case], "team-id")

        self.assertEqual("team-id", bound[0].task["spec"]["teamId"])
        self.assertEqual(case.task["scope"], bound[0].task["spec"]["scope"])
        self.assertEqual(case.task["inputJson"], bound[0].task["spec"]["inputJson"])
        self.assertNotIn("spec", case.task)

    def test_rejects_case_spec_bound_to_another_team(self):
        from scripts.agentteams_eval import EvalCase

        scope = {"tenant": "tenant-a", "project": "project-a", "team": "team-a"}
        case = EvalCase("case-a", "case-a", {"scope": scope,
            "spec": {"scope": scope, "teamId": "other-team-id"}}, [], {})
        with self.assertRaises(ENTRYPOINT.EvalError):
            ENTRYPOINT.bind_team_id([case], "team-id")

    def test_selects_only_requested_positive_case(self):
        from scripts.agentteams_eval import EvalCase

        cases = [EvalCase("negative", "negative", {}, [], {}),
                 EvalCase("multi-agent-pdf-positive", "positive", {}, [], {})]
        selected = ENTRYPOINT.select_cases(cases, ["multi-agent-pdf-positive"])
        self.assertEqual(["multi-agent-pdf-positive"], [case.case_id for case in selected])
        with self.assertRaises(ENTRYPOINT.EvalError):
            ENTRYPOINT.select_cases(cases, ["missing-case"])

    def test_parses_existing_task_ids_only_for_selected_cases(self):
        parsed = ENTRYPOINT.parse_existing_task_ids(
            ["case-a=11111111-1111-4111-8111-111111111111"], {"case-a", "case-b"})
        self.assertEqual({"case-a": "11111111-1111-4111-8111-111111111111"}, parsed)
        with self.assertRaises(ENTRYPOINT.EvalError):
            ENTRYPOINT.parse_existing_task_ids(["other=11111111-1111-4111-8111-111111111111"], {"case-a"})


class L5RefreshableTokenSessionTest(unittest.TestCase):
    def test_refreshes_token_after_api_401_and_retries_request(self):
        seen = []

        def direct_request(request, timeout):
            seen.append(request)
            return (401, {}, b"") if len(seen) == 1 else (200, {}, b"ok")

        class Opener:
            def open(self, request, timeout):
                self.request = request
                return io.BytesIO(json.dumps({"access_token": "new-access",
                                              "refresh_token": "new-refresh"}).encode())

        opener = Opener()
        session = ENTRYPOINT.RefreshableTokenSession(
            "old-access", "old-refresh", "http://keycloak/token", "http://control-plane")
        from urllib.request import Request
        request = Request("http://control-plane/api/v1/tasks/task-1", headers={"Authorization": "Bearer stale"})
        globals_for_session = ENTRYPOINT.RefreshableTokenSession.__call__.__globals__
        with patch.dict(globals_for_session, {"direct_request": direct_request}), \
             patch("urllib.request.build_opener", return_value=opener):
            status, _, body = session(request, 3)

        self.assertEqual((200, b"ok"), (status, body))
        self.assertEqual("Bearer old-access", seen[0].get_header("Authorization"))
        self.assertEqual("Bearer new-access", seen[1].get_header("Authorization"))
        self.assertEqual("new-refresh", session.refresh_token)

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

    def test_report_allows_only_sanitized_public_source_urls_in_source_evidence(self):
        report = {"cases": [{"evidenceBundle": {"sourceEvidence": {"calls": [{"sources": [{
            "title": "Official documentation", "url": "https://example.org/docs",
            "snippet": "Feature X is supported in version 2.",
        }]}]}}}]}

        ENTRYPOINT.validate_report_safety(report)

        report["cases"][0]["evidenceBundle"]["sourceEvidence"]["calls"][0]["sources"][0]["url"] = (
            "https://example.org/docs?X-Amz-Signature=secret")
        with self.assertRaises(ENTRYPOINT.EvalError):
            ENTRYPOINT.validate_report_safety(report)

    def test_refuses_to_create_report_file_when_token_would_be_serialized(self):
        with TemporaryDirectory() as temp_dir:
            report_path = Path(temp_dir) / "report.json"
            with self.assertRaises(ENTRYPOINT.EvalError):
                ENTRYPOINT.write_report(report_path, {"token": "short-lived-token-value"}, "short-lived-token-value")
            self.assertFalse(report_path.exists())


if __name__ == "__main__":
    unittest.main()
