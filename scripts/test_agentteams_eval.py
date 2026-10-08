import hashlib
import json
import runpy
import sys
import tempfile
import unittest
from io import BytesIO
from pathlib import Path
from pypdf import PdfWriter

from scripts.agentteams_eval import (
    AgentTeamsClient,
    EvalCase,
    build_evidence_bundle,
    evaluate_observation,
    load_cases,
    run_suite,
)


class EvaluationCoreTest(unittest.TestCase):
    def test_builds_sanitized_evidence_bundle_with_independent_input_status(self):
        bundle = build_evidence_bundle(
            {"id": "task-1", "phase": "SUCCEEDED", "spec": {"apiKey": "secret"}},
            {"id": "run-1"},
            [{"eventId": "event-1", "eventType": "tool.called", "payload": {"tool": "web_search", "token": "secret"}}],
            [{"taskId": "task-1"}],
            [],
            {"status": "SUCCEEDED", "summary": "ok", "artifacts": [{
                "name": "answer.md", "storageRef": "https://minio.invalid/result?X-Amz-Signature=secret",
                "sizeBytes": 10, "sha256": "a" * 64,
            }]}, {"status": "COMPLETE", "observedEventCount": 1, "eventLimit": 1000},
        )

        serialized = json.dumps(bundle, ensure_ascii=False).lower()
        self.assertEqual("COMPLETE", bundle["inputStatus"])
        self.assertEqual("artifact:" + "a" * 64, bundle["artifactRefs"][0]["storageRef"])
        self.assertNotIn("secret", serialized)
        self.assertNotIn("x-amz-signature", serialized)
        self.assertNotIn("downloadurl", serialized)

    def test_missing_result_manifest_marks_evidence_inconclusive(self):
        bundle = build_evidence_bundle({"id": "task-1"}, {"id": "run-1"}, [], [], [], None)

        self.assertEqual("INCONCLUSIVE", bundle["inputStatus"])

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
      taskType: agentscope
      prompt: Return ok
    assertions:
      - type: phase
        expected: SUCCEEDED
""",
                encoding="utf-8",
            )

            cases = load_cases(path)

        self.assertEqual(["sample"], [case.case_id for case in cases])
        self.assertEqual("agentscope", cases[0].task["taskType"])
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
                {"eventType": "tool.called", "sequence": 1, "payload": json.dumps({"tool": "web_search", "sequence": 1})},
                {"eventType": "tool.finished", "sequence": 2, "payload": json.dumps({"tool": "web_search", "sequence": 2, "ok": True})},
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

    def test_required_tool_requires_successful_finished_event_and_safe_reference(self):
        case = EvalCase("tool", "Tool", {}, [{"type": "required_tool", "name": "search"}], {})
        called_only = evaluate_observation(case, {"task": {}, "run": {"id": "r1"}, "events": [
            {"eventId": "e1", "eventType": "tool.called", "payload": {"tool": "search", "sequence": 1,
             "arguments": "private body"}}], "result": {}})[0]
        success = evaluate_observation(case, {"task": {}, "run": {"id": "r1"}, "events": [
            {"eventId": "e1", "eventType": "tool.called", "payload": {"tool": "search", "sequence": 1}},
            {"eventId": "e2", "eventType": "tool.finished", "payload": {"tool": "search", "sequence": 2, "ok": True}}], "result": {}})[0]

        self.assertEqual("INCONCLUSIVE", called_only.status)
        self.assertEqual("PASSED", success.status)
        self.assertEqual(("task-events:r1",), success.evidence_refs)
        self.assertNotIn("private body", json.dumps(called_only.as_dict()))

    def test_tool_lifecycle_order_count_and_failed_outcome(self):
        events = [
            {"eventId": "c1", "eventType": "tool.called", "sequence": 1, "payload": {"tool": "search", "sequence": 1}},
            {"eventId": "f1", "eventType": "tool.finished", "sequence": 2, "payload": {"tool": "search", "sequence": 2, "ok": False}},
            {"eventId": "c2", "eventType": "tool.called", "sequence": 3, "payload": {"tool": "fetch", "sequence": 3}},
            {"eventId": "f2", "eventType": "tool.finished", "sequence": 4, "payload": {"tool": "fetch", "sequence": 4, "ok": True}},
        ]
        case = EvalCase("life", "Lifecycle", {}, [
            {"type": "failed_tool", "name": "search"},
            {"type": "tool_order", "names": ["search", "fetch"]},
            {"type": "tool_count", "name": "search", "expected": 1}], {})
        results = evaluate_observation(case, {"run": {"id": "run-7"}, "events": events, "result": {}})

        self.assertEqual(["FAILED", "PASSED", "PASSED"], [result.status for result in results])
        self.assertTrue(all(result.evidence_refs == ("task-events:run-7",) for result in results))

    def test_dependency_assertions_require_success_and_handoff_reference(self):
        case = EvalCase("deps", "Dependencies", {}, [
            {"type": "dependency_success"}, {"type": "dependency_handoff"}], {})
        observation = {"run": {"id": "r"}, "events": [], "result": {}, "taskTree": [
            {"taskId": "a", "status": "SUCCEEDED", "dependencyIds": []},
            {"taskId": "b", "status": "SUCCEEDED", "dependencyIds": ["a"], "dependencyResults": {"a": "task-result:a"}}]}
        results = evaluate_observation(case, observation)

        self.assertEqual(["PASSED", "PASSED"], [result.status for result in results])
        observation["taskTree"][0]["status"] = "FAILED"
        self.assertEqual("FAILED", evaluate_observation(case, observation)[0].status)

    def test_truncated_events_and_missing_dependency_are_inconclusive(self):
        case = EvalCase("partial", "Partial", {}, [
            {"type": "required_tool", "name": "search"}, {"type": "dependency_success"}], {})
        results = evaluate_observation(case, {"run": {"id": "r"}, "events": [], "result": {},
                                              "evidenceCompleteness": {"status": "TRUNCATED"},
                                              "taskTree": [{"taskId": "b", "dependencyIds": ["missing"]}]})

        self.assertEqual(["INCONCLUSIVE", "INCONCLUSIVE"], [result.status for result in results])

    def test_pdf_metadata_and_signature_are_checked_from_bytes_only(self):
        case = EvalCase("pdf", "PDF", {}, [{"type": "artifact_pdf"}], {})
        buffer = BytesIO()
        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        writer.write(buffer)
        pdf_bytes = buffer.getvalue()
        good_meta = {"name": "report.pdf", "contentType": "application/pdf", "sizeBytes": len(pdf_bytes),
                     "sha256": hashlib.sha256(pdf_bytes).hexdigest(), "storageRef": "tasks/a/report.pdf",
                     "contentBytes": pdf_bytes}
        good = evaluate_observation(case, {"run": {"id": "r"}, "events": [], "result": {"artifacts": [good_meta]}})[0]
        bad = evaluate_observation(case, {"run": {"id": "r"}, "events": [], "result": {"artifacts": [
            {**good_meta, "contentType": "text/plain"}]}})[0]
        unavailable = evaluate_observation(case, {"run": {"id": "r"}, "events": [], "result": {"artifacts": [
            {key: value for key, value in good_meta.items() if key != "contentBytes"}]}})[0]

        self.assertEqual("PASSED", good.status)
        self.assertEqual("FAILED", bad.status)
        self.assertEqual("INCONCLUSIVE", unavailable.status)
        self.assertNotIn("contentBytes", json.dumps(good.as_dict()))

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
            if request.method == "GET" and path.endswith("/tree"):
                return 200, {}, b"[]"
            if request.method == "GET" and path.endswith("/decisions"):
                return 200, {}, b"[]"
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
             "GET /api/v1/tasks/task-1/runs/run-1/tree",
             "GET /api/v1/tasks/task-1/runs/run-1/decisions",
             "GET /api/v1/tasks/task-1"],
            [f"{request.method} {request.full_url.split('http://test', 1)[1]}" for request in calls],
        )
        for request in calls:
            self.assertEqual("Bearer test-token", request.headers["Authorization"])
        self.assertTrue(calls[0].headers.get("Idempotency-key"))
        self.assertTrue(calls[1].headers.get("Idempotency-key"))
        self.assertNotEqual(calls[0].headers["Idempotency-key"], calls[1].headers["Idempotency-key"])

    def test_client_paginates_process_events_and_marks_truncation_at_limit(self):
        event_requests = []
        def transport(request, timeout):
            path = request.full_url.split("http://test", 1)[1]
            if path.startswith("/api/v1/tasks/task-1/runs/run-1/process-events"):
                event_requests.append(path)
                after = int(path.split("after=", 1)[1]) if "after=" in path else 0
                if after == 0:
                    events = [{"eventId": f"e{n}", "sequence": n, "eventType": "note", "payload": {}}
                              for n in range(1, 101)]
                elif after == 100:
                    events = [{"eventId": f"e{n}", "sequence": n, "eventType": "note", "payload": {}}
                              for n in range(101, 201)]
                else:
                    events = []
                return 200, {}, json.dumps(events).encode()
            if path.endswith("/tree") or path.endswith("/decisions"):
                return 200, {}, b"[]"
            if path.endswith("/result"):
                return 200, {}, json.dumps({"status": "SUCCEEDED"}).encode()
            if path == "/api/v1/tasks/task-1":
                return 200, {}, json.dumps({"id": "task-1", "phase": "SUCCEEDED"}).encode()
            raise AssertionError(path)

        observation = AgentTeamsClient("http://test", "token", transport=transport).get_observation("task-1", "run-1")

        self.assertEqual(3, len(event_requests))
        self.assertEqual(200, observation["evidenceCompleteness"]["observedEventCount"])
        self.assertEqual("COMPLETE", observation["evidenceCompleteness"]["status"])

    def test_client_marks_event_limit_truncation_without_claiming_complete(self):
        def transport(request, timeout):
            path = request.full_url.split("http://test", 1)[1]
            if "/process-events" in path:
                after = int(path.split("after=", 1)[1]) if "after=" in path else 0
                events = [{"eventId": f"e{n}", "sequence": n, "eventType": "note", "payload": {}}
                          for n in range(after + 1, after + 101)]
                return 200, {}, json.dumps(events).encode()
            if path.endswith("/result"):
                return 200, {}, json.dumps({"status": "SUCCEEDED"}).encode()
            if path.endswith("/tree") or path.endswith("/decisions"):
                return 200, {}, b"[]"
            if path == "/api/v1/tasks/task-1":
                return 200, {}, json.dumps({"id": "task-1", "phase": "SUCCEEDED"}).encode()
            raise AssertionError(path)

        observation = AgentTeamsClient("http://test", "token", transport=transport).get_observation("task-1", "run-1")

        self.assertEqual("TRUNCATED", observation["evidenceCompleteness"]["status"])
        self.assertEqual(1001, observation["evidenceCompleteness"]["observedEventCount"])

    def test_core_suite_contains_credential_free_cases(self):
        cases = load_cases(Path(__file__).parents[1] / "eval/cases/core.yaml")
        self.assertEqual({"mcp-web-search-title", "no-tool-simple-answer", "artifact-integrity",
                          "multi-agent-pdf-positive"},
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
                return 200, {}, json.dumps([
                    {"eventType": "tool.called", "sequence": 1,
                     "payload": {"tool": "web_search", "sequence": 1, "arguments": "private"}},
                    {"eventType": "tool.finished", "sequence": 2,
                     "payload": {"tool": "web_search", "sequence": 2, "ok": True,
                                 "result": "private response"}},
                ]).encode()
            if request.method == "GET" and (path.endswith("/tree") or path.endswith("/decisions")):
                return 200, {}, b"[]"
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

    def test_script_path_can_load_deepeval_export_adapter(self):
        repository = Path(__file__).parents[1]
        script_directory = repository / "scripts"
        original_path = sys.path[:]
        loaded_eval_modules = {
            name: module for name, module in sys.modules.items()
            if name == "eval" or name.startswith("eval.")
        }
        try:
            sys.path = [str(script_directory)] + [
                entry for entry in original_path
                if Path(entry or ".").resolve() != repository.resolve()
            ]
            for name in list(loaded_eval_modules):
                sys.modules.pop(name, None)
            namespace = runpy.run_path(str(repository / "scripts/agentteams_eval.py"),
                                       run_name="agentteams_eval_cli")
            case = namespace["EvalCase"]("sample", "Sample", {
                "scope": {"tenant": "tenant-a", "project": "project-a", "team": "team-a"},
                "taskType": "agentscope",
                "inputJson": {"prompt": "Return ok"},
            }, [{"type": "phase", "expected": "SUCCEEDED"}], {})
            with tempfile.TemporaryDirectory() as directory:
                namespace["_write_deepeval_export"](
                    case,
                    {"task": {"phase": "SUCCEEDED"}, "result": {"summary": "ok"}},
                    Path(directory),
                )
                self.assertTrue((Path(directory) / "sample.json").exists())
        finally:
            sys.path = original_path
            for name in list(sys.modules):
                if name == "eval" or name.startswith("eval."):
                    sys.modules.pop(name, None)
            sys.modules.update(loaded_eval_modules)


if __name__ == "__main__":
    unittest.main()
