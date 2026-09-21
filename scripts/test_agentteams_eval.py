import json
import tempfile
import unittest
from pathlib import Path

from scripts.agentteams_eval import (
    AgentTeamsClient,
    EvalCase,
    evaluate_observation,
    load_cases,
    run_suite,
)


class EvaluationCoreTest(unittest.TestCase):
    def test_loads_yaml_case_with_task_and_assertions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cases.yaml"
            path.write_text(
                """
cases:
  - id: sample
    name: Sample case
    task:
      scope:
        tenant: tenant-a
        project: project-a
        team: team-a
      taskType: qwenpaw
      prompt: Return ok
    assertions:
      - type: phase
        expected: SUCCEEDED
""",
                encoding="utf-8",
            )

            cases = load_cases(path)

        self.assertEqual(["sample"], [case.case_id for case in cases])
        self.assertEqual("qwenpaw", cases[0].task["taskType"])
        self.assertEqual("SUCCEEDED", cases[0].assertions[0]["expected"])

    def test_evaluates_tool_and_output_assertions(self):
        case = EvalCase(
            case_id="mcp-title",
            name="MCP title",
            task={},
            assertions=[
                {"type": "phase", "expected": "SUCCEEDED"},
                {"type": "required_tool", "name": "web_search"},
                {"type": "forbidden_tool", "name": "shell"},
                {"type": "output_contains", "value": "Model Context Protocol"},
            ],
            limits={},
        )
        observation = {
            "task": {"id": "task-1", "phase": "SUCCEEDED"},
            "events": [
                {"eventType": "tool.called", "payload": json.dumps({"tool": "web_search"})}
            ],
            "result": {"summary": "Model Context Protocol"},
        }

        results = evaluate_observation(case, observation)

        self.assertEqual(4, len(results))
        self.assertTrue(all(result.passed for result in results))

    def test_reports_failed_hard_assertion(self):
        case = EvalCase(
            case_id="no-shell",
            name="No shell",
            task={},
            assertions=[{"type": "forbidden_tool", "name": "shell"}],
            limits={},
        )
        observation = {
            "task": {"phase": "SUCCEEDED"},
            "events": [
                {"eventType": "tool.called", "payload": {"tool": "shell"}}
            ],
            "result": {"summary": "ok"},
        }

        result = evaluate_observation(case, observation)[0]

        self.assertFalse(result.passed)
        self.assertIn("forbidden", result.message)

    def test_artifact_integrity_requires_durable_metadata(self):
        case = EvalCase(
            case_id="artifact",
            name="Artifact",
            task={},
            assertions=[{"type": "artifact_integrity"}],
            limits={},
        )
        observation = {
            "task": {"phase": "SUCCEEDED"},
            "events": [],
            "result": {
                "artifacts": [
                    {
                        "name": "result.json",
                        "storageRef": "tasks/task-1/attempts/attempt-1/artifacts/result.json",
                        "sizeBytes": 12,
                        "sha256": "a" * 64,
                        "downloadUrl": "http://minio.invalid/presigned-secret",
                    }
                ]
            },
        }

        result = evaluate_observation(case, observation)[0]

        self.assertTrue(result.passed)
        self.assertNotIn("downloadUrl", result.actual[0])

    def test_client_uses_auth_and_idempotency_for_task_lifecycle(self):
        calls = []

        def transport(request, timeout):
            calls.append(request)
            path = request.full_url.split("http://test", 1)[1]
            if request.method == "POST" and path == "/api/v1/tasks":
                return 201, {}, json.dumps({"id": "task-1", "version": 0}).encode()
            if request.method == "POST" and path == "/api/v1/tasks/task-1/queue":
                return 200, {}, json.dumps({"id": "task-1", "phase": "QUEUED", "version": 1}).encode()
            if request.method == "GET" and path == "/api/v1/tasks/task-1":
                return 200, {}, json.dumps({"id": "task-1", "phase": "SUCCEEDED"}).encode()
            if request.method == "GET" and path == "/api/v1/tasks/task-1/runs":
                return 200, {}, json.dumps([{"id": "run-1"}]).encode()
            if request.method == "GET" and path.endswith("/process-events"):
                return 200, {}, b"[]"
            if request.method == "GET" and path.endswith("/result"):
                return 200, {}, json.dumps({"summary": "ok"}).encode()
            raise AssertionError(f"unexpected request {request.method} {path}")

        case = EvalCase("sample", "Sample", {
            "scope": {"tenant": "tenant-a", "project": "project-a", "team": "team-a"},
            "prompt": "Return ok",
        }, [{"type": "phase", "expected": "SUCCEEDED"}], {})
        report = run_suite(AgentTeamsClient("http://test", "test-token", transport=transport),
                           [case], timeout_seconds=1, poll_seconds=0)

        self.assertTrue(report["passed"])
        self.assertEqual("task-1", report["cases"][0]["taskId"])
        self.assertEqual("run-1", report["cases"][0]["runId"])
        self.assertEqual(
            ["POST /api/v1/tasks", "POST /api/v1/tasks/task-1/queue",
             "GET /api/v1/tasks/task-1", "GET /api/v1/tasks/task-1/runs",
             "GET /api/v1/tasks/task-1/runs/run-1/process-events",
             "GET /api/v1/tasks/task-1/runs/run-1/result",
             "GET /api/v1/tasks/task-1"],
            [f"{request.method} {request.full_url.split('http://test', 1)[1]}" for request in calls],
        )
        for request in calls:
            self.assertEqual("Bearer test-token", request.headers["Authorization"])
        self.assertTrue(calls[0].headers.get("Idempotency-key"))
        self.assertTrue(calls[1].headers.get("Idempotency-key"))
        self.assertNotEqual(calls[0].headers["Idempotency-key"], calls[1].headers["Idempotency-key"])

    def test_core_suite_contains_three_credential_free_cases(self):
        cases = load_cases(Path(__file__).parents[1] / "eval/cases/core.yaml")
        self.assertEqual({"mcp-web-search-title", "no-tool-simple-answer", "artifact-integrity"},
                         {case.case_id for case in cases})
        for case in cases:
            self.assertTrue(case.task.get("taskType"))
            self.assertTrue(case.task.get("inputJson", {}).get("prompt"))
            self.assertTrue(case.assertions)
            serialized = json.dumps(case.task, ensure_ascii=False).lower()
            self.assertNotIn("api_key", serialized)
            self.assertNotIn("password", serialized)
            self.assertNotIn("cookie", serialized)

    def test_suite_can_export_sanitized_deepeval_case(self):
        calls = []

        def transport(request, timeout):
            calls.append(request)
            path = request.full_url.split("http://test", 1)[1]
            if request.method == "POST" and path == "/api/v1/tasks":
                return 201, {}, json.dumps({"id": "task-1", "version": 0}).encode()
            if request.method == "POST" and path == "/api/v1/tasks/task-1/queue":
                return 200, {}, json.dumps({"id": "task-1", "phase": "QUEUED", "version": 1}).encode()
            if request.method == "GET" and path == "/api/v1/tasks/task-1":
                return 200, {}, json.dumps({"id": "task-1", "phase": "SUCCEEDED"}).encode()
            if request.method == "GET" and path == "/api/v1/tasks/task-1/runs":
                return 200, {}, json.dumps([{"id": "run-1", "tokenCost": 3}]).encode()
            if request.method == "GET" and path.endswith("/process-events"):
                return 200, {}, json.dumps([{
                    "eventType": "tool.called",
                    "payload": {"tool": "web_search"},
                }]).encode()
            if request.method == "GET" and path.endswith("/result"):
                return 200, {}, json.dumps({
                    "summary": "Model Context Protocol",
                    "artifacts": [{
                        "name": "answer.md",
                        "downloadUrl": "https://minio.invalid/result?X-Amz-Signature=fixture",
                        "content": "Model Context Protocol",
                    }],
                }).encode()
            raise AssertionError(f"unexpected request {request.method} {path}")

        case = EvalCase("sample", "Sample", {
            "scope": {"tenant": "tenant-a", "project": "project-a", "team": "team-a"},
            "taskType": "chat",
            "inputJson": {"prompt": "Find the MCP title"},
        }, [
            {"type": "phase", "expected": "SUCCEEDED"},
            {"type": "required_tool", "name": "web_search"},
        ], {})
        with tempfile.TemporaryDirectory() as directory:
            export_dir = Path(directory) / "deepeval"
            report = run_suite(
                AgentTeamsClient("http://test", "test-token", transport=transport),
                [case], timeout_seconds=1, poll_seconds=0,
                deepeval_export_dir=export_dir,
            )
            exported = json.loads((export_dir / "sample.json").read_text(encoding="utf-8"))

        self.assertTrue(report["passed"])
        self.assertEqual("sample", exported["caseId"])
        serialized = json.dumps(exported, ensure_ascii=False).lower()
        self.assertNotIn("downloadurl", serialized)
        self.assertNotIn("x-amz-signature", serialized)


if __name__ == "__main__":
    unittest.main()
