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

    def test_evidence_bundle_uses_strict_allowlists_and_normalized_sensitive_keys(self):
        bundle = build_evidence_bundle(
            {"id": "task-1", "phase": "SUCCEEDED", "taskType": "agentscope", "prompt": "private prompt",
             "accessToken": "secret-a", "client-secret": "secret-b", "API_KEY": "secret-c"},
            {"id": "run-1", "status": "SUCCEEDED", "clientSecret": "secret-d"},
            [{"eventId": "e1", "eventType": "tool.finished", "payload": {
                "tool": "search", "sequence": 1, "ok": True, "clientSecret": "secret-e", "body": "private event"}}],
            [{"taskId": "task-1", "parentTaskId": None, "status": "SUCCEEDED",
              "dependencyIds": [{"taskId": "parent-1", "prompt": "hidden nested prompt"}],
              "dependencyResults": {"parent": "private result"}, "prompt": "private tree prompt"}],
            [{"id": "decision-1", "taskId": "task-1", "goalSummary": "private decision"}],
            {"status": "SUCCEEDED", "summary": "private summary", "output": "private output",
             "content": "private content", "apiKey": "secret-f", "artifacts": [{
                 "name": "safe.pdf", "contentType": "application/pdf", "storageRef": "tasks/task-1/safe.pdf",
                 "sizeBytes": 12, "sha256": "a" * 64, "downloadUrl": "https://x.invalid?X-Amz-Signature=secret"}]},
            {"status": "COMPLETE", "observedEventCount": 1, "eventLimit": 1000,
             "privateBody": "private completeness body", "apiKey": "secret-completeness"},
        )
        encoded = json.dumps(bundle, ensure_ascii=False)

        self.assertNotIn("private", encoded)
        self.assertNotIn("secret", encoded)
        self.assertNotIn("downloadUrl", encoded)
        self.assertEqual({"id", "phase", "taskType"}, set(bundle["taskSnapshot"]))
        self.assertEqual({"taskId", "parentTaskId", "status", "dependencyIds"}, set(bundle["taskTree"][0]))
        self.assertEqual(["parent-1"], bundle["taskTree"][0]["dependencyIds"])
        self.assertEqual({"id", "taskId"}, set(bundle["decisions"][0]))
        self.assertEqual({"status", "artifacts"}, set(bundle["resultManifest"]))
        self.assertEqual({"status", "observedEventCount", "eventLimit"}, set(bundle["evidenceCompleteness"]))

    def test_output_contains_report_has_no_expected_or_actual_body(self):
        case = EvalCase("contains", "Contains", {}, [{"type": "output_contains", "value": "private answer secret"}], {})
        result = evaluate_observation(case, {"run": {"id": "r"}, "result": {"summary": "private answer secret"}, "events": []})[0]
        report_json = json.dumps(result.as_dict())

        self.assertTrue(result.passed)
        self.assertIs(True, result.actual)
        self.assertNotIn("private answer", report_json)
        self.assertNotIn("secret", report_json)

    def test_artifact_metadata_and_assertion_actual_redact_common_credentials(self):
        hostile = {
            "name": "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature",
            "storageRef": "tasks/task-1/result?token=sk-live-12345678901234567890",
            "contentType": "application/pdf Bearer sk-test-12345678901234567890",
            "sizeBytes": 12,
            "sha256": "a" * 64,
            "version": "token=sk-test-12345678901234567890",
            "stage": "FINAL token=sk-test-12345678901234567890",
            "visibility": "REQUESTER token=sk-test-12345678901234567890",
        }
        bundle = build_evidence_bundle(
            {"id": "task-1"}, {"id": "run-1"}, [], [], [],
            {"status": "SUCCEEDED", "artifacts": [hostile]})
        case = EvalCase("artifact-safe", "Artifact safe", {}, [{"type": "artifact_integrity"}], {})
        report_json = json.dumps(evaluate_observation(case, {"result": {"artifacts": [hostile]}})[0].as_dict())
        encoded = json.dumps(bundle, ensure_ascii=False) + report_json

        self.assertNotIn("eyJhbGci", encoded)
        self.assertNotIn("sk-test-12345678901234567890", encoded)
        self.assertNotIn("sk-live-12345678901234567890", encoded)
        self.assertEqual("artifact", bundle["artifactRefs"][0]["name"])
        self.assertEqual("artifact:" + "a" * 64, bundle["artifactRefs"][0]["storageRef"])
        self.assertNotIn("contentType", bundle["artifactRefs"][0])
        self.assertNotIn("version", bundle["artifactRefs"][0])
        self.assertNotIn("stage", bundle["artifactRefs"][0])
        self.assertNotIn("visibility", bundle["artifactRefs"][0])

    def test_opaque_tasks_storage_path_is_replaced_with_digest_reference(self):
        bundle = build_evidence_bundle(
            {"id": "task-1"}, {"id": "run-1"}, [], [], [],
            {"status": "SUCCEEDED", "artifacts": [{
                "name": "report.pdf", "storageRef": "tasks/opaqueCredentialValue",
                "sizeBytes": 12, "sha256": "b" * 64,
            }]})

        self.assertEqual("artifact:" + "b" * 64, bundle["artifactRefs"][0]["storageRef"])

    def test_artifact_integrity_rejects_boolean_and_float_sizes(self):
        case = EvalCase("artifact-size", "Artifact size", {}, [{"type": "artifact_integrity"}], {})
        base = {"name": "result.json", "storageRef": "tasks/11111111-1111-1111-1111-111111111111/attempts/22222222-2222-2222-2222-222222222222/artifacts/result.json",
                "sha256": "a" * 64}

        statuses = [evaluate_observation(case, {"result": {"artifacts": [{**base, "sizeBytes": size}]}})[0].status
                    for size in (True, 1.5)]
        self.assertEqual(["FAILED", "FAILED"], statuses)

    def test_signed_url_variants_are_removed_as_whole_urls(self):
        bundles = [build_evidence_bundle({"id": "task-1"}, {"id": "run-1", "status": url}, [], [], [], None)
                   for url in ("https://storage.example/file?X-Goog-Signature=secret",
                               "https://storage.example/file?Signature=secret")]

        for bundle in bundles:
            self.assertEqual("[REDACTED_URL]", bundle["runStatus"])
            self.assertNotIn("storage.example", json.dumps(bundle))
            self.assertNotIn("secret", json.dumps(bundle))

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
            "eventCompleteness": "COMPLETE",
            "task": {"id": "task-1", "phase": "SUCCEEDED"},
            "events": [
                {"eventType": "tool.called", "sequence": 1, "payload": json.dumps({"tool": "web_search", "sequence": 1, "callId": "web-1"})},
                {"eventType": "tool.finished", "sequence": 2, "payload": json.dumps({"tool": "web_search", "sequence": 2, "callId": "web-1", "ok": True})},
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
            {"eventId": "e1", "eventType": "tool.called", "payload": {"tool": "search", "sequence": 1, "callId": "call-1"}},
            {"eventId": "e2", "eventType": "tool.finished", "payload": {"tool": "search", "sequence": 2, "callId": "call-1", "ok": True}}], "eventCompleteness": "COMPLETE", "result": {}})[0]

        self.assertEqual("INCONCLUSIVE", called_only.status)
        self.assertEqual("PASSED", success.status)
        self.assertEqual(("task-events:r1",), success.evidence_refs)
        self.assertNotIn("private body", json.dumps(called_only.as_dict()))

    def test_required_tool_is_inconclusive_when_any_call_has_no_completion(self):
        case = EvalCase("tool-retries", "Tool retries", {}, [{"type": "required_tool", "name": "search"}], {})
        events = [
            {"eventType": "tool.called", "sequence": 1, "payload": {"tool": "search", "sequence": 1}},
            {"eventType": "tool.finished", "sequence": 2, "payload": {"tool": "search", "sequence": 2, "ok": True}},
            {"eventType": "tool.called", "sequence": 3, "payload": {"tool": "search", "sequence": 3}},
        ]

        self.assertEqual("INCONCLUSIVE", evaluate_observation(case, {"events": events})[0].status)
        events.append({"eventType": "tool.finished", "sequence": 4,
                       "payload": {"tool": "search", "sequence": 4, "ok": False}})
        self.assertEqual("INCONCLUSIVE", evaluate_observation(case, {"events": events})[0].status)

    def test_tool_lifecycle_order_count_and_failed_outcome(self):
        events = [
            {"eventId": "c1", "eventType": "tool.called", "sequence": 1, "payload": {"tool": "search", "sequence": 1, "callId": "search-1"}},
            {"eventId": "f1", "eventType": "tool.finished", "sequence": 2, "payload": {"tool": "search", "sequence": 2, "callId": "search-1", "ok": False}},
            {"eventId": "c2", "eventType": "tool.called", "sequence": 3, "payload": {"tool": "fetch", "sequence": 3, "callId": "fetch-1"}},
            {"eventId": "f2", "eventType": "tool.finished", "sequence": 4, "payload": {"tool": "fetch", "sequence": 4, "callId": "fetch-1", "ok": True}},
        ]
        case = EvalCase("life", "Lifecycle", {}, [
            {"type": "failed_tool", "name": "search"},
            {"type": "tool_order", "names": ["search", "fetch"]},
            {"type": "tool_count", "name": "search", "expected": 1}], {})
        results = evaluate_observation(case, {"run": {"id": "run-7"}, "events": events, "result": {},
                                              "eventCompleteness": "COMPLETE"})

        self.assertEqual(["FAILED", "PASSED", "PASSED"], [result.status for result in results])
        self.assertTrue(all(result.evidence_refs == ("task-events:run-7",) for result in results))

    def test_failed_tool_fails_as_soon_as_failure_is_observed(self):
        case = EvalCase("tool-failure", "Tool failure", {}, [{"type": "failed_tool", "name": "search"}], {})
        events = [
            {"eventType": "tool.called", "sequence": 1, "payload": {"tool": "search", "sequence": 1, "callId": "call-1"}},
            {"eventType": "tool.called", "sequence": 2, "payload": {"tool": "search", "sequence": 2, "callId": "call-2"}},
            {"eventType": "tool.finished", "sequence": 3, "payload": {"tool": "search", "sequence": 3, "callId": "call-2", "ok": False}},
        ]

        self.assertEqual("FAILED", evaluate_observation(case, {"events": events})[0].status)

    def test_observed_failed_tool_still_fails_when_event_stream_is_truncated(self):
        case = EvalCase("tool-truncated", "Tool truncated", {}, [{"type": "failed_tool", "name": "search"}], {})
        observation = {"events": [
            {"eventType": "tool.called", "sequence": 1, "payload": {"tool": "search", "sequence": 1, "callId": "call-1"}},
            {"eventType": "tool.finished", "sequence": 2, "payload": {"tool": "search", "sequence": 2, "callId": "call-1", "ok": False}},
        ], "evidenceCompleteness": {"status": "TRUNCATED", "observedEventCount": 1001, "eventLimit": 1000}}

        self.assertEqual("FAILED", evaluate_observation(case, observation)[0].status)

    def test_truncated_failed_tool_requires_unique_correlated_call_id(self):
        case = EvalCase("failed-pair", "Failed pair", {}, [{"type": "failed_tool", "name": "search"}], {})
        def event(kind, call_id, sequence, ok=None):
            payload = {"tool": "search", "sequence": sequence}
            if call_id is not None:
                payload["callId"] = call_id
            if ok is not None:
                payload["ok"] = ok
            return {"eventType": kind, "sequence": sequence, "payload": payload}
        def status(events):
            return evaluate_observation(case, {"events": events, "eventCompleteness": "TRUNCATED"})[0].status

        valid = [event("tool.called", "call-1", 1), event("tool.finished", "call-1", 2, False)]
        no_id = [event("tool.called", None, 1), event("tool.finished", None, 2, False)]
        orphan = [event("tool.finished", "call-1", 2, False)]
        duplicate = [event("tool.called", "call-1", 1), event("tool.called", "call-1", 2),
                     event("tool.finished", "call-1", 3, False)]
        mismatch = [event("tool.called", "call-1", 1), event("tool.finished", "call-2", 2, False)]

        self.assertEqual("FAILED", status(valid))
        self.assertEqual(["INCONCLUSIVE"] * 4, [status(events) for events in (no_id, orphan, duplicate, mismatch)])

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

    def test_dependency_assertions_ignore_event_truncation_and_validate_safe_handoff_refs(self):
        case = EvalCase("deps", "Dependencies", {}, [
            {"type": "dependency_success"}, {"type": "dependency_handoff"}], {})
        tree = [
            {"taskId": "a", "status": "SUCCEEDED", "dependencyIds": []},
            {"taskId": "b", "status": "SUCCEEDED", "dependencyIds": ["a"],
             "dependencyResults": {"a": "task-result:a?token=secret"}},
        ]
        results = evaluate_observation(case, {"taskTree": tree, "events": [],
                                              "evidenceCompleteness": {"status": "TRUNCATED"}})
        self.assertEqual(["PASSED", "FAILED"], [result.status for result in results])
        tree[1]["dependencyResults"]["a"] = "task-result:a"
        self.assertEqual("PASSED", evaluate_observation(case, {"taskTree": tree, "events": []})[1].status)

    def test_retry_limit_and_unknown_assertions_are_inconclusive(self):
        case = EvalCase("retry", "Retry", {}, [
            {"type": "retry_limit", "maxAttempts": 2}, {"type": "not_supported"}], {})

        self.assertEqual(["INCONCLUSIVE", "INCONCLUSIVE"],
                         [result.status for result in evaluate_observation(case, {"events": []})])

    def test_dependency_success_without_edges_is_inconclusive_when_required(self):
        case = EvalCase("deps", "Dependencies", {}, [{"type": "dependency_success", "requireDependencies": True}], {})

        result = evaluate_observation(case, {"taskTree": [{"taskId": "root", "status": "SUCCEEDED",
                                                             "dependencyIds": []}]})[0]
        self.assertEqual("INCONCLUSIVE", result.status)

    def test_event_cursor_incomplete_never_passes_required_tool(self):
        case = EvalCase("partial-events", "Partial", {}, [{"type": "required_tool", "name": "search"}], {})
        events = [{"eventType": "tool.called", "sequence": 1, "payload": {"tool": "search", "sequence": 1}},
                  {"eventType": "tool.finished", "sequence": 2, "payload": {"tool": "search", "sequence": 2, "ok": True}}]

        result = evaluate_observation(case, {"events": events, "eventCompleteness": "INCONCLUSIVE"})[0]
        self.assertEqual("INCONCLUSIVE", result.status)

    def test_pagination_failure_preserves_raw_completeness_and_event_status(self):
        def transport(request, timeout):
            path = request.full_url.split("http://test", 1)[1]
            if "/process-events" in path:
                events = [{"eventType": "note", "payload": {}} for _ in range(100)]
                return 200, {}, json.dumps(events).encode()
            if path.endswith("/result"):
                return 200, {}, json.dumps({"status": "SUCCEEDED"}).encode()
            if path.endswith("/tree") or path.endswith("/decisions"):
                return 200, {}, b"[]"
            if path == "/api/v1/tasks/task-1":
                return 200, {}, json.dumps({"id": "task-1", "phase": "SUCCEEDED"}).encode()
            raise AssertionError(path)

        observation = AgentTeamsClient("http://test", "token", transport=transport).get_observation("task-1", "run-1")
        bundle = build_evidence_bundle(observation["task"], observation["run"], observation["events"],
                                       observation["taskTree"], observation["decisions"], observation["result"],
                                       observation["evidenceCompleteness"], observation["eventCompleteness"])

        self.assertEqual("INCONCLUSIVE", observation["evidenceCompleteness"]["status"])
        self.assertEqual("INCONCLUSIVE", observation["eventCompleteness"])
        self.assertEqual(observation["evidenceCompleteness"], bundle["evidenceCompleteness"])
        self.assertEqual("INCONCLUSIVE", bundle["eventCompleteness"])

    def test_upstream_event_inconclusive_survives_local_pagination_completion(self):
        def transport(request, timeout):
            path = request.full_url.split("http://test", 1)[1]
            if "/process-events" in path:
                return 200, {}, json.dumps({"events": [], "evidenceCompleteness": {
                    "status": "INCONCLUSIVE", "observedEventCount": 0, "eventLimit": 1000,
                    "privateBody": "must not be retained"}}).encode()
            if path.endswith("/result"):
                return 200, {}, json.dumps({"status": "SUCCEEDED"}).encode()
            if path.endswith("/tree") or path.endswith("/decisions"):
                return 200, {}, b"[]"
            if path == "/api/v1/tasks/task-1":
                return 200, {}, json.dumps({"id": "task-1", "phase": "SUCCEEDED"}).encode()
            raise AssertionError(path)

        observation = AgentTeamsClient("http://test", "token", transport=transport).get_observation("task-1", "run-1")
        self.assertEqual("INCONCLUSIVE", observation["eventCompleteness"])
        self.assertEqual({"status": "INCONCLUSIVE", "observedEventCount": 0, "eventLimit": 1000},
                         observation["evidenceCompleteness"])

    def test_lifecycle_rejects_completion_before_call_sequence(self):
        events = [
            {"eventType": "tool.finished", "sequence": 1,
             "payload": {"tool": "search", "sequence": 1, "callId": "order-1", "ok": False}},
            {"eventType": "tool.called", "sequence": 2,
             "payload": {"tool": "search", "sequence": 2, "callId": "order-1"}},
        ]
        case = EvalCase("wrong-order", "Wrong order", {}, [
            {"type": "required_tool", "name": "search"}, {"type": "failed_tool", "name": "search"}], {})
        complete = evaluate_observation(case, {"events": events, "eventCompleteness": "COMPLETE"})
        truncated = evaluate_observation(case, {"events": events, "eventCompleteness": "TRUNCATED"})

        self.assertEqual(["INCONCLUSIVE", "INCONCLUSIVE"], [result.status for result in complete])
        self.assertEqual(["INCONCLUSIVE", "INCONCLUSIVE"], [result.status for result in truncated])

    def test_tool_lifecycle_requires_unique_matching_call_ids(self):
        case = EvalCase("pair", "Pair", {}, [{"type": "required_tool", "name": "search"}], {})
        def event(call_id, event_type, sequence, ok=None):
            payload = {"tool": "search", "sequence": sequence, "callId": call_id}
            if ok is not None:
                payload["ok"] = ok
            return {"eventType": event_type, "sequence": sequence, "payload": payload}
        matched = [event("call-1", "tool.called", 1), event("call-1", "tool.finished", 2, True)]
        missing_id = [event("call-1", "tool.called", 1), event(None, "tool.finished", 2, True)]
        duplicate = [event("call-1", "tool.called", 1), event("call-1", "tool.called", 2),
                     event("call-1", "tool.finished", 3, True)]

        self.assertEqual("PASSED", evaluate_observation(case, {"events": matched, "eventCompleteness": "COMPLETE"})[0].status)
        self.assertEqual("INCONCLUSIVE", evaluate_observation(case, {"events": missing_id, "eventCompleteness": "COMPLETE"})[0].status)
        self.assertEqual("INCONCLUSIVE", evaluate_observation(case, {"events": duplicate, "eventCompleteness": "COMPLETE"})[0].status)

    def test_tool_lifecycle_rejects_call_id_collision_across_tools(self):
        events = [
            {"eventType": "tool.called", "sequence": 1,
             "payload": {"tool": "search", "sequence": 1, "callId": "collision-x"}},
            {"eventType": "tool.finished", "sequence": 2,
             "payload": {"tool": "search", "sequence": 2, "callId": "collision-x", "ok": False}},
            {"eventType": "tool.called", "sequence": 3,
             "payload": {"tool": "fetch", "sequence": 3, "callId": "collision-x"}},
        ]
        case = EvalCase("collision", "Collision", {}, [
            {"type": "required_tool", "name": "search"},
            {"type": "failed_tool", "name": "search"},
        ], {})

        results = evaluate_observation(case, {"events": events, "eventCompleteness": "COMPLETE"})

        self.assertEqual(["INCONCLUSIVE", "INCONCLUSIVE"], [result.status for result in results])

    def test_run_status_is_inconclusive_when_run_snapshot_is_missing(self):
        case = EvalCase("run", "Run", {}, [{"type": "run_status", "expected": "SUCCEEDED"}], {})

        self.assertEqual("INCONCLUSIVE", evaluate_observation(case, {"events": []})[0].status)

    def test_truncated_events_and_missing_dependency_are_inconclusive(self):
        case = EvalCase("partial", "Partial", {}, [
            {"type": "required_tool", "name": "search"}, {"type": "dependency_success"}], {})
        results = evaluate_observation(case, {"run": {"id": "r"}, "events": [], "result": {},
                                              "evidenceCompleteness": {"status": "TRUNCATED"},
                                              "taskTree": [{"taskId": "b", "dependencyIds": ["missing"]}]})

        self.assertEqual(["INCONCLUSIVE", "INCONCLUSIVE"], [result.status for result in results])

    def test_pdf_metadata_and_signature_are_checked_from_bytes_only(self):
        case = EvalCase("pdf", "PDF", {}, [{"type": "artifact_pdf", "exactlyOne": True,
                                             "durable": True, "downloadable": True}], {})
        buffer = BytesIO()
        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        writer.write(buffer)
        pdf_bytes = buffer.getvalue()
        good_meta = {"name": "report.pdf", "contentType": "application/pdf", "sizeBytes": len(pdf_bytes),
                     "sha256": hashlib.sha256(pdf_bytes).hexdigest(), "storageRef": "tasks/11111111-1111-1111-1111-111111111111/attempts/22222222-2222-2222-2222-222222222222/artifacts/report.pdf",
                     "downloadUrl": "https://storage.example/download?X-Amz-Signature=secret",
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
        self.assertNotIn("X-Amz-Signature", json.dumps(good.as_dict()))
        multiple = evaluate_observation(case, {"run": {"id": "r"}, "events": [], "result": {
            "artifacts": [good_meta, {**good_meta, "name": "second.pdf",
                                       "storageRef": "tasks/11111111-1111-1111-1111-111111111111/attempts/22222222-2222-2222-2222-222222222222/artifacts/second.pdf"}]}})[0]
        non_durable = evaluate_observation(case, {"run": {"id": "r"}, "events": [], "result": {
            "artifacts": [{**good_meta, "storageRef": "https://storage.example/report.pdf"}]}})[0]
        missing_download = evaluate_observation(case, {"run": {"id": "r"}, "events": [], "result": {
            "artifacts": [{key: value for key, value in good_meta.items() if key != "downloadUrl"}]}})[0]
        malformed_url = evaluate_observation(case, {"run": {"id": "r"}, "events": [], "result": {
            "artifacts": [{**good_meta, "downloadUrl": "https://[broken-ipv6/report.pdf"}]}})[0]
        self.assertEqual(["FAILED", "FAILED", "FAILED", "FAILED"],
                         [multiple.status, non_durable.status, missing_download.status, malformed_url.status])

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
                        "storageRef": "tasks/11111111-1111-1111-1111-111111111111/attempts/22222222-2222-2222-2222-222222222222/artifacts/result.json",
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
        pdf_case = next(case for case in cases if case.case_id == "multi-agent-pdf-positive")
        self.assertIn({"type": "dependency_success", "requireDependencies": True}, pdf_case.assertions)
        self.assertIn({"type": "required_tool", "name": "load_skill_through_path"}, pdf_case.assertions)
        self.assertIn({"type": "artifact_pdf", "exactlyOne": True, "durable": True,
                       "downloadable": True}, pdf_case.assertions)
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
                     "payload": {"tool": "web_search", "sequence": 1, "callId": "web-1", "arguments": "private"}},
                    {"eventType": "tool.finished", "sequence": 2,
                     "payload": {"tool": "web_search", "sequence": 2, "callId": "web-1", "ok": True,
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
