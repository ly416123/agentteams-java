import hashlib
import json
import runpy
import sys
import tempfile
import unittest
from io import BytesIO
from pathlib import Path
from datetime import datetime
from pypdf import PdfWriter

from scripts.agentteams_eval import (
    AgentTeamsClient,
    EvalCase,
    EvalTransportError,
    MAX_TEXT_ARTIFACT_BYTES,
    _NoRedirect,
    _read_limited,
    build_evidence_bundle,
    evaluate_observation,
    load_cases,
    run_suite,
)


class EvaluationCoreTest(unittest.TestCase):
    def test_get_run_rejects_latest_run_after_cutoff_instead_of_falling_back(self):
        runs = [
            {"id": "future-run", "startedAt": "2026-10-09T05:10:54Z", "completedAt": "2026-10-09T05:11:54Z"},
            {"id": "eligible-run", "startedAt": "2026-10-09T05:04:54Z", "completedAt": "2026-10-09T05:04:59Z"},
        ]

        def transport(request, timeout):
            return 200, {}, json.dumps(runs).encode()

        client = AgentTeamsClient("http://test", "token", transport=transport)

        with self.assertRaisesRegex(Exception, "no execution run before the target run"):
            client.get_run("task-1", before=datetime.fromisoformat("2026-10-09T05:05:00+00:00"))

    def test_source_observation_uses_only_child_runs_completed_before_parent_run_starts(self):
        parent_id, parent_run, child_id = "parent-1", "parent-run", "child-1"
        child_run = "eligible-child-run"
        url = "https://www.w3.org/TR/WCAG20-TECHS/pdf.html"
        paths = []

        def transport(request, timeout):
            path = request.full_url.split("http://test", 1)[1]
            paths.append(path)
            if path.endswith("/process-events"):
                if f"/tasks/{child_id}/" in path:
                    return 200, {}, json.dumps([{"sequence": 1, "eventType": "tool.called",
                        "payload": {"tool": "web_search", "callId": "call-1"}}]).encode()
                return 200, {}, b"[]"
            if path.endswith("/result"):
                return 200, {}, json.dumps({"status": "SUCCEEDED", "summary": url, "artifacts": []}).encode()
            if path.endswith("/tree"):
                return 200, {}, json.dumps([{"taskId": child_id, "parentTaskId": parent_id,
                    "status": "SUCCEEDED", "dependencyIds": [], "updatedAt": "2026-10-09T05:04:59Z"}]).encode()
            if path.endswith("/decisions"):
                return 200, {}, b"[]"
            if path == f"/api/v1/tasks/{parent_id}/runs":
                return 200, {}, json.dumps([{"id": parent_run, "startedAt": "2026-10-09T05:05:00Z",
                    "completedAt": "2026-10-09T05:20:00Z"}]).encode()
            if path == f"/api/v1/tasks/{child_id}/runs":
                return 200, {}, json.dumps([
                    {"id": "future-child-run", "status": "SUCCEEDED", "startedAt": "2026-10-09T05:10:00Z",
                        "completedAt": "2026-10-09T05:10:30Z"},
                    {"id": child_run, "status": "SUCCEEDED", "startedAt": "2026-10-09T05:04:00Z",
                        "completedAt": "2026-10-09T05:04:30Z"},
                ]).encode()
            if path.endswith("/source-evidence"):
                if f"/tasks/{child_id}/runs/{child_run}/" in path:
                    return 200, {}, json.dumps({"taskId": child_id, "runId": child_run,
                        "truncated": False, "observedCallCount": 1, "observedSourceCount": 1,
                        "calls": [{"taskId": child_id, "runId": child_run, "callId": "call-1",
                            "complete": True, "sources": [{"title": "W3C", "url": url,
                                "evidenceRef": "task-source:call-1:" + "a" * 64}]}]}).encode()
                if f"/tasks/{parent_id}/" in path:
                    return 200, {}, json.dumps({"taskId": parent_id, "runId": parent_run,
                        "truncated": False, "observedCallCount": 0, "observedSourceCount": 0,
                        "calls": []}).encode()
            if path == f"/api/v1/tasks/{parent_id}":
                return 200, {}, json.dumps({"id": parent_id, "phase": "SUCCEEDED"}).encode()
            if path == f"/api/v1/tasks/{child_id}":
                return 200, {}, json.dumps({"id": child_id, "phase": "SUCCEEDED"}).encode()
            raise AssertionError(path)

        observation = AgentTeamsClient("http://test", "token", transport=transport).get_observation(
            parent_id, parent_run, include_source_evidence=True)

        self.assertEqual("INCONCLUSIVE", observation["sourceEvidence"]["completeness"]["status"],
                         json.dumps({"evidence": observation["sourceEvidence"], "paths": paths}, indent=2))
        self.assertEqual([[parent_id, parent_run]],
                         observation["sourceEvidence"]["allowedTaskRuns"])
        self.assertFalse(any(f"/tasks/{child_id}/runs/{child_run}/source-evidence" in path for path in paths))

    def test_get_run_selects_newest_run_when_api_returns_newest_first(self):
        runs = [
            {"id": "aggregate-run", "startedAt": "2026-10-09T05:10:54Z"},
            {"id": "planning-run", "startedAt": "2026-10-09T05:09:43Z"},
        ]

        def transport(request, timeout):
            return 200, {}, json.dumps(runs).encode()

        client = AgentTeamsClient("http://test", "token", transport=transport)

        self.assertEqual("aggregate-run", client.get_run("task-1")["id"])

    def test_run_suite_counts_search_calls_from_successful_child_runs(self):
        parent_id, child_id = "parent-1", "child-1"
        parent_run, child_run = "parent-run", "child-run"
        url = "https://www.w3.org/TR/WCAG20-TECHS/pdf.html"
        source_evidence = {
            "taskId": parent_id,
            "runId": parent_run,
            "allowedTaskRuns": [[parent_id, parent_run], [child_id, child_run]],
            "calls": [{"taskId": child_id, "runId": child_run, "callId": "search-1",
                       "complete": True, "sources": [{"url": url,
                       "evidenceRef": "task-source:search-1:" + "a" * 64}]}],
            "completeness": {"status": "COMPLETE", "observedSearchCalls": 1,
                             "recordedSearchCalls": 1, "observedSources": 1},
        }

        class FakeClient:
            token = "test-token"

            def create_task(self, case):
                return {"id": parent_id, "version": 0}

            def queue_task(self, task_id, version):
                pass

            def wait_for_terminal(self, task_id, timeout_seconds, poll_seconds):
                pass

            def get_run(self, task_id):
                return {"id": parent_run, "status": "SUCCEEDED"}

            def get_observation(self, task_id, run_id, include_source_evidence=False):
                return {
                    "task": {"id": parent_id, "phase": "SUCCEEDED"},
                    "run": {"id": parent_run, "status": "SUCCEEDED"},
                    "events": [{"sequence": 1, "eventType": "tool.called",
                                "payload": {"tool": "plan_subtasks", "sequence": 1}}],
                    "subtaskEvents": [{"taskId": child_id, "runId": child_run, "sequence": 1,
                                       "eventType": "tool.called",
                                       "payload": {"tool": "web_search", "sequence": 1}}],
                    "eventCompleteness": "COMPLETE",
                    "evidenceCompleteness": {"status": "COMPLETE", "observedEventCount": 1,
                                              "eventLimit": 1000},
                    "result": {"status": "SUCCEEDED", "summary": url, "artifacts": []},
                    "sourceEvidence": source_evidence,
                    "taskTree": [{"taskId": child_id, "parentTaskId": parent_id,
                                  "status": "SUCCEEDED", "dependencyIds": []}],
                    "decisions": [],
                }

        case = EvalCase("delegated-source", "Delegated source", {},
                        [{"type": "source_citation"}], {})
        report = run_suite(FakeClient(), [case], None, 0.01)
        self.assertEqual("PASSED", report["cases"][0]["assertions"][0]["status"])
        self.assertEqual("COMPLETE", report["cases"][0]["evidenceBundle"]["sourceEvidence"]
                         ["completeness"]["status"])

    def test_source_citation_uses_only_complete_task_run_bound_sources(self):
        url = "https://www.w3.org/TR/WCAG20-TECHS/pdf.html"
        call = {"callId": "call-1", "complete": True, "sources": [
            {"title": "W3C", "url": url, "evidenceRef": "task-source:call-1:" + "a" * 64}]}
        observation = {"task": {"id": "task-1"}, "run": {"id": "run-1"}, "result": {"summary": "See " + url},
                       "sourceEvidence": {"taskId": "task-1", "runId": "run-1", "allowedTaskRuns": [["task-1", "run-1"]],
                                          "calls": [{**call, "taskId": "task-1", "runId": "run-1"}],
                                          "completeness": {"status": "COMPLETE", "observedSearchCalls": 1,
                                                           "recordedSearchCalls": 1}}}
        case = EvalCase("citation", "Citation", {}, [{"type": "source_citation"}], {})
        self.assertEqual("PASSED", evaluate_observation(case, observation)[0].status)
        observation["result"]["summary"] = "See https://example.test/fake"
        self.assertEqual("FAILED", evaluate_observation(case, observation)[0].status)
        observation["result"]["summary"] = "See " + url + " and https://example.test/fake"
        self.assertEqual("FAILED", evaluate_observation(case, observation)[0].status)
        observation["sourceEvidence"]["runId"] = "other-run"
        self.assertEqual("INCONCLUSIVE", evaluate_observation(case, observation)[0].status)

    def test_source_citation_can_require_one_verified_url_while_allowing_other_tool_sources(self):
        url = "https://www.w3.org/TR/mcp/"
        call = {"taskId": "task-1", "runId": "run-1", "complete": True,
                "sources": [{"url": url, "evidenceRef": "task-source:call-1:" + "a" * 64}]}
        observation = {"task": {"id": "task-1"}, "run": {"id": "run-1"},
                       "result": {"summary": "Task completed", "artifacts": [
                           {"name": "output.md", "content": "News: https://news.example/article\nOfficial: " + url}]},
                       "sourceEvidence": {"taskId": "task-1", "runId": "run-1",
                           "allowedTaskRuns": [["task-1", "run-1"]], "calls": [call],
                           "completeness": {"status": "COMPLETE", "observedSearchCalls": 1,
                                            "recordedSearchCalls": 1}}}
        case = EvalCase("citation", "Citation", {}, [
            {"type": "source_citation", "allowUnverifiedCitations": True}], {})

        self.assertEqual("PASSED", evaluate_observation(case, observation)[0].status)
        observation["result"]["artifacts"][0]["content"] = "News: https://news.example/article"
        self.assertEqual("FAILED", evaluate_observation(case, observation)[0].status)

    def test_source_citation_reads_final_output_artifact_when_manifest_summary_is_generic(self):
        url = "https://www.w3.org/TR/mcp/"
        observation = {
            "task": {"id": "task-1"},
            "run": {"id": "run-1"},
            "result": {"summary": "Task completed", "artifacts": [
                {"name": "output.md", "content": "Sources:\n" + url},
            ]},
            "sourceEvidence": {
                "taskId": "task-1", "runId": "run-1", "allowedTaskRuns": [["task-1", "run-1"]],
                "calls": [{"taskId": "task-1", "runId": "run-1", "complete": True,
                           "sources": [{"url": url, "evidenceRef": "task-source:call-1:" + "a" * 64}]}],
                "completeness": {"status": "COMPLETE", "observedSearchCalls": 1,
                                 "recordedSearchCalls": 1},
            },
        }
        case = EvalCase("citation", "Citation", {}, [{"type": "source_citation"}], {})

        self.assertEqual("PASSED", evaluate_observation(case, observation)[0].status)

    def test_source_citation_decodes_summary_from_json_wrapped_aggregate_result(self):
        url = "https://www.w3.org/WAI/WCAG21/Techniques"
        observation = {
            "task": {"id": "task-1"},
            "run": {"id": "run-1"},
            "result": {"summary": "```json\n" + json.dumps({"summary": "Sources:\n" + url + "\nVerified source."}) + "\n```"},
            "sourceEvidence": {
                "taskId": "task-1", "runId": "run-1", "allowedTaskRuns": [["task-1", "run-1"]],
                "calls": [{"taskId": "task-1", "runId": "run-1", "complete": True,
                           "sources": [{"url": url, "evidenceRef": "task-source:call-1:" + "a" * 64}]}],
                "completeness": {"status": "COMPLETE", "observedSearchCalls": 1,
                                 "recordedSearchCalls": 1},
            },
        }
        case = EvalCase("citation", "Citation", {}, [{"type": "source_citation"}], {})

        self.assertEqual("PASSED", evaluate_observation(case, observation)[0].status)

    def test_result_output_artifact_is_downloaded_only_from_configured_origin(self):
        task_id, run_id = "task-1", "run-1"
        content = b"Sources:\nhttps://www.w3.org/TR/mcp/\n"
        digest = hashlib.sha256(content).hexdigest()
        requested = []

        def transport(request, timeout):
            requested.append((request.full_url, dict(request.header_items())))
            path = request.full_url.split("http://control-plane", 1)[-1]
            if path.endswith("/process-events"):
                return 200, {}, b"[]"
            if path.endswith("/result"):
                payload = {"status": "SUCCEEDED", "summary": "Task completed",
                           "artifacts": [{"name": "output.md", "contentType": "text/markdown; charset=UTF-8",
                                          "sizeBytes": len(content), "sha256": digest,
                                          "downloadUrl": "http://files.example:30090/signed?sig=redacted"}]}
                return 200, {}, json.dumps(payload).encode()
            if path.endswith("/tree") or path.endswith("/decisions"):
                return 200, {}, b"[]"
            if request.full_url.startswith("http://files.example:30090/"):
                return 200, {"Content-Type": "text/markdown; charset=UTF-8"}, content
            if path == f"/api/v1/tasks/{task_id}/runs":
                return 200, {}, json.dumps([{"id": run_id, "startedAt": "2026-10-09T05:05:00Z"}]).encode()
            if path == f"/api/v1/tasks/{task_id}":
                return 200, {}, json.dumps({"id": task_id, "phase": "SUCCEEDED"}).encode()
            raise AssertionError(request.full_url)

        client = AgentTeamsClient("http://control-plane", "token", transport=transport,
                                  allowed_artifact_origins=("http://files.example:30090",))
        observation = client.get_observation(task_id, run_id)

        self.assertEqual(content.decode(), observation["result"]["artifacts"][0]["content"])
        artifact_requests = [headers for url, headers in requested if url.startswith("http://files.example")]
        self.assertEqual(1, len(artifact_requests))
        self.assertNotIn("Authorization", artifact_requests[0])
        bundle = build_evidence_bundle(observation["task"], observation["run"], observation["events"],
                                       observation["taskTree"], observation["decisions"], observation["result"])
        self.assertNotIn(content.decode(), json.dumps(bundle))

    def test_output_artifact_download_rejects_bad_metadata_and_response(self):
        content = b"markdown"
        digest = hashlib.sha256(content).hexdigest()
        requests = []
        client = AgentTeamsClient("http://control-plane", "token",
                                  transport=lambda request, timeout: requests.append(request) or
                                  (200, {"Content-Type": "text/markdown"}, content),
                                  allowed_artifact_origins=("http://files.example:30090",))
        base = {"downloadUrl": "http://files.example:30090/output.md?sig=redacted",
                "sizeBytes": len(content), "sha256": digest}

        self.assertIsNone(client._request_text_artifact({**base, "sizeBytes": MAX_TEXT_ARTIFACT_BYTES + 1}))
        self.assertIsNone(client._request_text_artifact({**base, "downloadUrl": "http://files.example:30090/output.md"}))
        self.assertEqual([], requests)
        self.assertIsNone(client._request_text_artifact({**base, "sha256": "0" * 64}))
        self.assertEqual(1, len(requests))

        bad_mime = AgentTeamsClient("http://control-plane", "token",
                                    transport=lambda request, timeout: (200, {"Content-Type": "application/pdf"}, content),
                                    allowed_artifact_origins=("http://files.example:30090",))
        self.assertIsNone(bad_mime._request_text_artifact(base))
        bad_utf8 = AgentTeamsClient("http://control-plane", "token",
                                    transport=lambda request, timeout: (200, {"Content-Type": "text/plain"}, b"\xff"),
                                    allowed_artifact_origins=("http://files.example:30090",))
        self.assertIsNone(bad_utf8._request_text_artifact({**base, "sizeBytes": 1,
                                                           "sha256": hashlib.sha256(b"\xff").hexdigest()}))

    def test_output_artifact_download_failure_never_exposes_signed_url(self):
        signed_url = "http://files.example:30090/object?X-Amz-Signature=secret-value"
        def transport(request, timeout):
            raise EvalTransportError(f"network failure for {request.full_url}")

        client = AgentTeamsClient("http://control-plane", "token", transport=transport,
                                  allowed_artifact_origins=("http://files.example:30090",))
        artifact = {"downloadUrl": signed_url, "sizeBytes": 12, "sha256": "a" * 64}

        self.assertIsNone(client._request_text_artifact(artifact))

    def test_output_artifact_download_rejects_unapproved_origin_without_request(self):
        requests = []
        client = AgentTeamsClient("http://control-plane", "token",
                                  transport=lambda request, timeout: requests.append(request) or (200, {}, b""),
                                  allowed_artifact_origins=("http://files.example:30090",))

        self.assertIsNone(client._request_text_artifact({
            "downloadUrl": "http://127.0.0.1:9000/object?sig=redacted",
            "sizeBytes": 1, "sha256": "a" * 64}))
        self.assertEqual([], requests)

    def test_output_artifact_response_reads_at_most_limit_plus_one_byte(self):
        class ReadProbe:
            requested = None
            def read(self, size):
                self.requested = size
                return b"x" * size

        response = ReadProbe()
        data = _read_limited(response, MAX_TEXT_ARTIFACT_BYTES)

        self.assertEqual(MAX_TEXT_ARTIFACT_BYTES + 1, len(data))
        self.assertEqual(MAX_TEXT_ARTIFACT_BYTES + 1, response.requested)

    def test_artifact_redirect_handler_refuses_redirects(self):
        handler = _NoRedirect()

        self.assertIsNone(handler.redirect_request(None, None, 302, "Found", {},
                                                   "http://elsewhere.invalid/"))

    def test_source_evidence_fetch_is_scoped_and_export_redacts_source_urls(self):
        task_id, run_id = "task-1", "run-1"
        url = "https://example.test/source"
        paths = []
        def transport(request, timeout):
            path = request.full_url.split("http://test", 1)[1]
            paths.append(path)
            if path.endswith("/process-events"):
                return 200, {}, json.dumps([
                    {"sequence": 1, "eventType": "tool.called",
                     "payload": {"tool": "web_search", "callId": "call-1"}},
                    {"sequence": 2, "eventType": "tool.called",
                     "payload": {"tool": "memory_search", "callId": "memory-1"}},
                    {"sequence": 3, "eventType": "tool.called",
                     "payload": {"tool": "session_search", "callId": "session-1"}},
                ]).encode()
            if path.endswith("/result"): return 200, {}, json.dumps({"status": "SUCCEEDED", "summary": "See " + url, "artifacts": []}).encode()
            if path.endswith("/tree") or path.endswith("/decisions"): return 200, {}, b"[]"
            if path.endswith("/source-evidence"):
                return 200, {}, json.dumps({"taskId": task_id, "runId": run_id, "truncated": False,
                    "observedCallCount": 1, "observedSourceCount": 1, "calls": [{"taskId": task_id, "runId": run_id, "callId": "call-1",
                    "complete": True, "sources": [{"title": "source", "url": url,
                    "evidenceRef": "task-source:call-1:" + "a" * 64}]}]}).encode()
            if path == f"/api/v1/tasks/{task_id}/runs":
                return 200, {}, json.dumps([{"id": run_id, "startedAt": "2026-10-09T05:05:00Z"}]).encode()
            if path == f"/api/v1/tasks/{task_id}": return 200, {}, json.dumps({"id": task_id, "phase": "SUCCEEDED"}).encode()
            raise AssertionError(path)
        client = AgentTeamsClient("http://test", "token", transport=transport)
        observation = client.get_observation(task_id, run_id, include_source_evidence=True)
        self.assertEqual("COMPLETE", observation["sourceEvidence"]["completeness"]["status"])
        self.assertEqual(1, observation["sourceEvidence"]["completeness"]["observedSearchCalls"])
        case = EvalCase("citation", "Citation", {}, [{"type": "source_citation"}], {})
        self.assertEqual("PASSED", evaluate_observation(case, observation)[0].status)
        bundle = build_evidence_bundle(observation["task"], observation["run"], observation["events"],
                                       observation["taskTree"], observation["decisions"], observation["result"],
                                       source_evidence=observation["sourceEvidence"])
        self.assertNotIn(url, json.dumps(bundle))
        self.assertIn(f"/api/v1/tasks/{task_id}/runs/{run_id}/source-evidence", paths)

    def test_missing_child_run_marks_observation_inconclusive_instead_of_aborting(self):
        task_id, run_id, child_id = "parent-1", "parent-run", "child-1"

        def transport(request, timeout):
            path = request.full_url.split("http://test", 1)[1]
            if path.endswith("/process-events") or path.endswith("/decisions"):
                return 200, {}, b"[]"
            if path.endswith("/result"):
                return 200, {}, b'{"status":"SUCCEEDED","summary":"done","artifacts":[]}'
            if path.endswith("/tree"):
                return 200, {}, json.dumps([{"taskId": child_id, "parentTaskId": task_id,
                    "status": "PENDING", "dependencyIds": [],
                    "updatedAt": "2026-10-09T05:04:00Z"}]).encode()
            if path.endswith("/source-evidence"):
                return 200, {}, json.dumps({"taskId": task_id, "runId": run_id, "truncated": False,
                    "observedCallCount": 0, "observedSourceCount": 0, "calls": []}).encode()
            if path == f"/api/v1/tasks/{task_id}":
                return 200, {}, json.dumps({"id": task_id, "phase": "SUCCEEDED"}).encode()
            if path == f"/api/v1/tasks/{child_id}":
                return 200, {}, json.dumps({"id": child_id, "phase": "DRAFT"}).encode()
            if path == f"/api/v1/tasks/{child_id}/runs":
                return 200, {}, b"[]"
            if path == f"/api/v1/tasks/{task_id}/runs":
                return 200, {}, json.dumps([{"id": run_id, "startedAt": "2026-10-09T05:05:00Z"}]).encode()
            raise AssertionError(path)

        client = AgentTeamsClient("http://test", "token", transport=transport)

        observation = client.get_observation(task_id, run_id, include_source_evidence=True)

        self.assertEqual("INCONCLUSIVE", observation["eventCompleteness"])
        self.assertEqual("INCONCLUSIVE", observation["sourceEvidence"]["completeness"]["status"])
        self.assertEqual([], observation["subtaskEvents"])

    def test_collects_scoped_child_tool_events_and_task_file_pdf(self):
        from io import BytesIO
        from pypdf import PdfWriter

        writer = PdfWriter()
        writer.add_blank_page(width=72, height=72)
        pdf = BytesIO()
        writer.write(pdf)
        content = pdf.getvalue()
        digest = hashlib.sha256(content).hexdigest()
        parent_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        child_id, file_id = "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"

        def transport(request, timeout):
            path = request.full_url.split("http://test", 1)[1]
            if path.endswith("/process-events") and child_id in path:
                events = [
                    {"taskId": child_id, "runId": "child-run", "sequence": 1,
                     "eventType": "tool.called", "payload": {"tool": "web_search", "callId": "call-1"}},
                    {"taskId": child_id, "runId": "child-run", "sequence": 2,
                     "eventType": "tool.finished", "payload": {"tool": "web_search", "callId": "call-1", "ok": True}},
                ]
                return 200, {}, json.dumps(events).encode()
            if path.endswith("/process-events"):
                return 200, {}, b"[]"
            if path.endswith("/result"):
                return 200, {}, json.dumps({"status": "SUCCEEDED", "artifacts": [], "taskFiles": [
                    {"fileId": file_id, "role": "OUTPUT", "name": "report.pdf", "status": "AVAILABLE"}]}).encode()
            if path.endswith("/tree"):
                return 200, {}, json.dumps([{"taskId": child_id, "parentTaskId": parent_id,
                    "status": "PENDING", "dependencyIds": [], "updatedAt": "2026-10-09T05:04:00Z"}]).encode()
            if path.endswith("/decisions"):
                return 200, {}, b"[]"
            if path.endswith("/source-evidence"):
                if f"/tasks/{child_id}/" in path:
                    return 200, {}, json.dumps({"taskId": child_id, "runId": "child-run", "truncated": False,
                        "observedCallCount": 1, "observedSourceCount": 1, "calls": [{"taskId": child_id,
                        "runId": "child-run", "callId": "child-call", "complete": True, "sources": [{
                        "title": "W3C", "url": "https://www.w3.org/TR/WCAG20-TECHS/pdf.html",
                        "evidenceRef": "task-source:child-call:" + "b" * 64}]}]}).encode()
                return 200, {}, json.dumps({"taskId": parent_id, "runId": "parent-run", "truncated": False,
                    "observedCallCount": 0, "observedSourceCount": 0, "calls": []}).encode()
            if path == f"/api/v1/tasks/{child_id}":
                return 200, {}, json.dumps({"id": child_id, "phase": "SUCCEEDED"}).encode()
            if path == f"/api/v1/tasks/{child_id}/runs":
                return 200, {}, json.dumps([{"id": "child-run", "status": "SUCCEEDED",
                    "startedAt": "2026-10-09T05:04:00Z", "completedAt": "2026-10-09T05:04:30Z"}]).encode()
            if path == f"/api/v1/tasks/{parent_id}/runs":
                return 200, {}, json.dumps([{"id": "parent-run", "startedAt": "2026-10-09T05:05:00Z",
                    "completedAt": "2026-10-09T05:20:00Z"}]).encode()
            if path == f"/api/v1/tasks/{parent_id}/files?role=OUTPUT":
                return 200, {}, json.dumps([{"fileId": file_id, "role": "OUTPUT", "name": "report.pdf",
                    "contentType": "application/pdf", "sizeBytes": len(content), "sha256": digest,
                    "status": "AVAILABLE"}]).encode()
            if path.endswith(f"/files/{file_id}/content"):
                return 200, {}, content
            if path == f"/api/v1/tasks/{parent_id}":
                return 200, {}, json.dumps({"id": parent_id, "phase": "SUCCEEDED"}).encode()
            raise AssertionError(path)

        observation = AgentTeamsClient("http://test", "token", transport=transport).get_observation(
            parent_id, "parent-run", include_source_evidence=True)
        case = EvalCase("combined", "combined", {}, [
            {"type": "required_tool", "name": "web_search"},
            {"type": "artifact_pdf", "exactlyOne": True, "durable": True, "downloadable": True}], {})
        outcomes = evaluate_observation(case, observation)
        self.assertEqual(["PASSED", "PASSED"], [item.status for item in outcomes], [item.message for item in outcomes])
        self.assertEqual("SUCCEEDED", observation["taskTree"][0]["status"])
        self.assertEqual(child_id, observation["subtaskEvents"][0]["taskId"])
        self.assertEqual("COMPLETE", observation["sourceEvidence"]["completeness"]["status"])
        self.assertEqual(child_id, observation["sourceEvidence"]["calls"][0]["taskId"])
        self.assertNotIn("contentBytes", json.dumps(build_evidence_bundle(
            observation["task"], {"id": "parent-run"}, observation["events"], observation["taskTree"],
            observation["decisions"], observation["result"], subtask_events=observation["subtaskEvents"])))

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

    def test_evidence_bundle_preserves_only_valid_dependency_handoff_refs(self):
        dependency = "11111111-1111-4111-8111-111111111111"
        bundle = build_evidence_bundle(
            {"id": "task"}, {"id": "run"}, [],
            [{"taskId": "child", "status": "SUCCEEDED", "dependencyIds": [dependency],
              "dependencyResultRefs": {dependency: f"task-result:{dependency}", "unrelated": "secret result"},
              "dependencyResults": [{"taskId": dependency, "result": "private body"}]}], [], None)

        self.assertEqual({dependency: f"task-result:{dependency}"}, bundle["taskTree"][0]["dependencyResultRefs"])
        self.assertNotIn("private body", json.dumps(bundle))

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
                    for size in (True, 1.5, 0)]
        missing = evaluate_observation(case, {"result": {"artifacts": [base]}})[0]
        self.assertEqual(["FAILED"] * 4, statuses + [missing.status])

    def test_artifact_integrity_rejects_non_durable_path_and_hides_original_name(self):
        case = EvalCase("artifact-safe", "Artifact safe", {}, [{"type": "artifact_integrity"}], {})
        hostile_name = "customer private report bearer abc1234567890123456789"
        result = evaluate_observation(case, {"result": {"artifacts": [{
            "name": hostile_name, "storageRef": "tasks/opaqueCredentialValue", "sizeBytes": 5,
            "sha256": "a" * 64,
        }]}})[0]

        self.assertEqual("FAILED", result.status)
        self.assertNotIn(hostile_name, result.message)
        self.assertNotIn(hostile_name, json.dumps(result.as_dict()))

    def test_signed_url_variants_are_removed_as_whole_urls(self):
        statuses = ["https://storage.example/file?X-Goog-Signature=secret",
                    "https://storage.example/file?Signature=secret",
                    "prefix https://storage.example/file?X-Goog-Signature=secret suffix",
                    "prefix https://storage.example/file?Signature=secret"]
        bundles = [build_evidence_bundle({"id": "task-1"}, {"id": "run-1", "status": status}, [], [], [], None)
                   for status in statuses]

        self.assertEqual(["[REDACTED_URL]", "[REDACTED_URL]",
                          "prefix [REDACTED_URL] suffix", "prefix [REDACTED_URL]"],
                         [bundle["runStatus"] for bundle in bundles])
        for bundle in bundles:
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
        invalid_boolean_metadata = {**good_meta, "sizeBytes": True}
        invalid_boolean_metadata.pop("contentBytes")
        boolean_size = evaluate_observation(case, {"run": {"id": "r"}, "events": [], "result": {
            "artifacts": [invalid_boolean_metadata]}})[0]
        self.assertEqual("FAILED", boolean_size.status)

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
        }, [{"type": "phase", "expected": "SUCCEEDED"}], {"timeoutSeconds": 300})
        client = AgentTeamsClient("http://test", "test-token", transport=transport)
        timeouts = []
        wait_for_terminal = client.wait_for_terminal
        def record_timeout(task_id, timeout_seconds, poll_seconds):
            timeouts.append(timeout_seconds)
            return wait_for_terminal(task_id, 0, poll_seconds)
        client.wait_for_terminal = record_timeout
        report = run_suite(client, [case], timeout_seconds=None, poll_seconds=0)

        self.assertTrue(report["passed"])
        self.assertEqual([300], timeouts)
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

    def test_client_error_does_not_log_untrusted_response_body(self):
        from scripts.agentteams_eval import EvalTransportError

        body = b'{"error":"secret context https://storage.example/file?X-Goog-Signature=leak"}'
        client = AgentTeamsClient("http://test", "test-token",
                                  transport=lambda request, timeout: (403, {}, body))

        with self.assertRaises(EvalTransportError) as raised:
            client._request("GET", "/api/v1/tasks/task-1")

        message = str(raised.exception)
        self.assertIn("403", message)
        self.assertNotIn("secret context", message)
        self.assertNotIn("storage.example", message)
        self.assertNotIn("leak", message)

    def test_client_marks_denied_process_event_reads_inconclusive(self):
        client = AgentTeamsClient("http://test", "test-token",
                                  transport=lambda request, timeout: (403, {}, b"denied"))

        events, status = client._collect_events("task-1", "run-1")

        self.assertEqual([], events)
        self.assertEqual("INCONCLUSIVE", status)

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
        self.assertIn({"type": "required_tool", "name": "web_search"}, pdf_case.assertions)
        self.assertNotIn({"type": "required_tool", "name": "load_skill_through_path"}, pdf_case.assertions)
        self.assertEqual(["plan_subtasks"], pdf_case.task["inputJson"]["requiredMcpTools"])
        self.assertEqual(["web_search"],
                         pdf_case.task["inputJson"]["executorMcpTools"])
        self.assertEqual(["create_task_pdf_artifact"],
                         pdf_case.task["inputJson"]["aggregateMcpTools"])
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
