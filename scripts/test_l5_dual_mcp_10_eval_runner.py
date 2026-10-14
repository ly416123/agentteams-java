#!/usr/bin/env python3
"""Unit checks for the L5 ten-case runner's token renewal behavior."""

import json
import runpy
import unittest
from io import BytesIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from urllib.request import Request


ROOT = Path(__file__).resolve().parents[1]
NAMESPACE = runpy.run_path(str(ROOT / "scripts/run-l5-dual-mcp-10-eval.py"))
GLOBALS = NAMESPACE["TokenSession"].__call__.__globals__


class TokenSessionTest(unittest.TestCase):
    def test_retries_transient_connection_reset_on_api_get(self):
        calls = []

        def fake_request(request, timeout):
            calls.append(request)
            if len(calls) == 1:
                raise ConnectionResetError("connection reset by peer")
            return 200, {}, b"ok"

        session = NAMESPACE["TokenSession"]("access", "refresh")
        request = Request(NAMESPACE["API_URL"] + "/api/v1/tasks/example")
        with patch.dict(GLOBALS, {"direct_request": fake_request}), \
             patch("time.sleep") as sleep:
            status, _, body = session(request, 1)

        self.assertEqual((200, b"ok"), (status, body))
        self.assertEqual(2, len(calls))
        sleep.assert_called_once_with(1)

    def test_refreshes_after_api_401_and_retries_with_new_bearer(self):
        seen = []

        def fake_request(request, timeout):
            seen.append(request)
            if len(seen) == 1:
                return 401, {}, b""
            return 200, {}, b"ok"

        class Opener:
            def open(self, request, timeout):
                self.request = request
                return BytesIO(json.dumps({"access_token": "new-access",
                                           "refresh_token": "new-refresh"}).encode())

        opener = Opener()
        session = NAMESPACE["TokenSession"]("old-access", "old-refresh")
        request = Request(NAMESPACE["API_URL"] + "/api/v1/tasks/example",
                          headers={"Authorization": "Bearer stale"})
        with patch.dict(GLOBALS, {"direct_request": fake_request}), \
             patch("urllib.request.build_opener", return_value=opener):
            status, _, body = session(request, 1)

        self.assertEqual((200, b"ok"), (status, body))
        self.assertEqual("Bearer old-access", seen[0].get_header("Authorization"))
        self.assertEqual("Bearer new-access", seen[1].get_header("Authorization"))
        self.assertEqual("new-refresh", session.refresh_token)


class SystemicFailureStopTest(unittest.TestCase):
    def test_stops_when_required_mcp_has_execution_failure(self):
        should_stop = NAMESPACE["should_stop_after_case"]
        self.assertTrue(should_stop({"observedToolFailure": {
            "tool": "web_search", "outcome": "TOOL_EXECUTION_FAILED"}}))

    def test_stops_when_required_tool_assertion_confirms_failed_call(self):
        should_stop = NAMESPACE["should_stop_after_case"]
        self.assertTrue(should_stop({"assertions": [{"type": "required_tool", "passed": False,
                                                       "message": "required tool web_search failed"}]}))

    def test_stops_when_case_times_out_before_terminal_phase(self):
        should_stop = NAMESPACE["should_stop_after_case"]
        self.assertFalse(should_stop({"error": "task abc did not reach terminal phase within 480s"}))

    def test_does_not_stop_on_case_level_assertion_failure(self):
        should_stop = NAMESPACE["should_stop_after_case"]
        self.assertFalse(should_stop({"evaluationStatus": "FAILED", "assertions": [
            {"passed": False, "type": "source_citation"}]}))

    def test_resume_skips_terminal_results_and_retries_running_case(self):
        is_terminal_case = NAMESPACE["is_terminal_case"]
        self.assertTrue(is_terminal_case({"evaluationStatus": "FAILED"}))
        self.assertTrue(is_terminal_case({"evaluationStatus": "INCONCLUSIVE"}))
        self.assertFalse(is_terminal_case({"evaluationStatus": "RUNNING"}))

    def test_resumed_results_keep_suite_order(self):
        ordered_cases = NAMESPACE["ordered_cases"]
        cases = [SimpleNamespace(case_id="first"), SimpleNamespace(case_id="second")]
        self.assertEqual(["first", "second"], [item["caseId"] for item in ordered_cases(
            [{"caseId": "second"}, {"caseId": "first"}], cases)])


if __name__ == "__main__":
    unittest.main()
