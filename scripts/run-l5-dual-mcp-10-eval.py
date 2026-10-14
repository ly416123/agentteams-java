#!/usr/bin/env python3
"""Run the ten-case dual-MCP acceptance suite serially against L5."""

from __future__ import annotations

import getpass
import hashlib
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.agentteams_eval import AgentTeamsClient, EvalError, EvalTransportError, load_cases, run_suite

L5 = "http://192.168.122.55"
TOKEN_URL = f"{L5}:30082/realms/agentteams/protocol/openid-connect/token"
API_URL = f"{L5}:30080"
ARTIFACT_ORIGIN = f"{L5}:30090"
CASE_FILE = ROOT / "eval/cases/ai-news-web-search-10.yaml"
REPORT = ROOT / "eval/reports/2026-10-13-l5-dual-mcp-10-parent-pdf-after-r5.json"
EVIDENCE_DIR = ROOT / "eval/reports/2026-10-13-l5-dual-mcp-10-parent-pdf-after-r5-evidence"
DEEPEVAL_EXPORT_DIR = ROOT / "eval/reports/2026-10-13-l5-dual-mcp-10-parent-pdf-after-r5-deepeval-inputs"


def direct_request(request, timeout):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=timeout) as response:
            return response.status, response.headers, response.read(25 * 1024 * 1024 + 1)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.headers, exc.read(25 * 1024 * 1024 + 1)
    except urllib.error.URLError as exc:
        raise EvalTransportError(f"L5 request failed: {exc.reason}") from exc


def acquire_token() -> tuple[str, str]:
    password = getpass.getpass("quota-admin password (hidden): ")
    body = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "agentteams-api",
        "username": "quota-admin", "password": password,
    }).encode()
    del password
    request = urllib.request.Request(TOKEN_URL, data=body,
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=15) as response:
            payload = json.load(response)
            token, refresh = payload.get("access_token"), payload.get("refresh_token")
    except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise EvalError(f"Keycloak token request failed ({type(exc).__name__}); no evaluation started") from exc
    if not isinstance(token, str) or not token or not isinstance(refresh, str) or not refresh:
        raise EvalError("Keycloak returned no access/refresh token; no evaluation started")
    return token, refresh


class TokenSession:
    """Refresh the short-lived access token once after an API 401."""

    def __init__(self, access_token: str, refresh_token: str):
        self.access_token = access_token
        self.refresh_token = refresh_token

    def __call__(self, request, timeout):
        if not request.full_url.startswith(API_URL):
            return direct_request(request, timeout)
        status, headers, body = self._api_request(request, timeout)
        if status != 401:
            return status, headers, body
        self._refresh(timeout)
        return self._api_request(request, timeout)

    def _api_request(self, request, timeout):
        # A reset while polling task state is transient, and GET is safe to retry.
        # Never retry API mutations here because their outcome may be ambiguous.
        attempts = 3 if request.get_method() == "GET" else 1
        for attempt in range(attempts):
            try:
                return direct_request(self._with_access_token(request), timeout)
            except (EvalTransportError, ConnectionError, TimeoutError):
                if attempt + 1 == attempts:
                    raise
                time.sleep(attempt + 1)

    def _with_access_token(self, request):
        headers = dict(request.header_items())
        headers["Authorization"] = "Bearer " + self.access_token
        return urllib.request.Request(request.full_url, data=request.data, headers=headers,
                                      method=request.get_method())

    def _refresh(self, timeout):
        body = urllib.parse.urlencode({
            "grant_type": "refresh_token", "client_id": "agentteams-api",
            "refresh_token": self.refresh_token,
        }).encode()
        request = urllib.request.Request(TOKEN_URL, data=body,
                                         headers={"Content-Type": "application/x-www-form-urlencoded"})
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=timeout) as response:
                payload = json.load(response)
        except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError) as exc:
            raise EvalTransportError(f"Keycloak access-token refresh failed ({type(exc).__name__})") from exc
        access = payload.get("access_token")
        refresh = payload.get("refresh_token", self.refresh_token)
        if not isinstance(access, str) or not access or not isinstance(refresh, str) or not refresh:
            raise EvalTransportError("Keycloak returned an invalid refreshed token")
        self.access_token, self.refresh_token = access, refresh


def should_stop_after_case(item):
    if not isinstance(item, dict):
        return False
    if item.get("error"):
        error = str(item["error"]).lower()
        if "did not reach terminal phase within" in error or "did not reach terminal status within" in error:
            return False
        return True
    failure = item.get("observedToolFailure") if isinstance(item, dict) else None
    if isinstance(failure, dict) and failure.get("outcome") == "TOOL_EXECUTION_FAILED":
        return True
    return any(isinstance(assertion, dict)
               and assertion.get("type") == "required_tool"
               and assertion.get("passed") is False
               and " failed" in str(assertion.get("message", "")).lower()
               for assertion in item.get("assertions", []))


def is_terminal_case(item):
    return isinstance(item, dict) and item.get("evaluationStatus") in {
        "PASSED", "FAILED", "INCONCLUSIVE"
    }


def ordered_cases(items, suite_cases):
    positions = {case.case_id: index for index, case in enumerate(suite_cases)}
    return sorted(items, key=lambda item: positions.get(item.get("caseId"), len(positions)))


def main() -> int:
    token, refresh_token = acquire_token()
    token_session = TokenSession(token, refresh_token)
    cases = load_cases(CASE_FILE)
    client = AgentTeamsClient(API_URL, token, timeout=30, transport=token_session,
                              allowed_artifact_origins=(ARTIFACT_ORIGIN,))
    from runpy import run_path
    l5_runner = run_path(str(ROOT / "scripts/run-l5-agentteams-eval.py"))
    project_id, team_id = l5_runner["assert_scope_access"](client, "tenant-a", "project-a", "team-a")
    cases = l5_runner["bind_team_id"](cases, team_id)
    suite_hash = hashlib.sha256(CASE_FILE.read_bytes()).hexdigest()
    suite_started = datetime.now(timezone.utc).isoformat()
    completed = []
    resume_ids = {}
    if REPORT.exists():
        try:
            previous = json.loads(REPORT.read_text(encoding="utf-8"))
            if previous.get("suiteVersion") == suite_hash:
                for item in previous.get("cases", []):
                    if is_terminal_case(item):
                        completed.append(item)
                    elif item.get("evaluationStatus") == "RUNNING" and item.get("caseId") and item.get("taskId"):
                        resume_ids[item["caseId"]] = item["taskId"]
        except (OSError, json.JSONDecodeError):
            pass
    completed_ids = {item.get("caseId") for item in completed}
    completed = ordered_cases(completed, cases)
    active_item = None
    infrastructure_error = None
    try:
        for index, case in enumerate(cases, start=1):
            if case.case_id in completed_ids:
                continue
            existing_task_id = resume_ids.get(case.case_id)
            active_item = {"caseId": case.case_id, "taskId": existing_task_id,
                           "passed": False, "score": 0.0, "assertions": [],
                           "evaluationStatus": "RUNNING"}
            write_progress(ordered_cases(completed + [active_item], cases), suite_hash, suite_started, token)
            if existing_task_id:
                print(json.dumps({"case": index, "total": len(cases), "caseId": case.case_id,
                                  "resumingTaskId": existing_task_id}, ensure_ascii=False), flush=True)
            else:
                original_create_task = client.create_task
                def create_and_record(current_case):
                    nonlocal active_item
                    created = original_create_task(current_case)
                    active_item["taskId"] = str(created.get("id", ""))
                    write_progress(ordered_cases(completed + [active_item], cases), suite_hash, suite_started, token)
                    print(json.dumps({"case": index, "total": len(cases), "caseId": case.case_id,
                                      "taskId": active_item["taskId"]}, ensure_ascii=False), flush=True)
                    return created
                client.create_task = create_and_record
            report = run_suite(client, [case], timeout_seconds=None, poll_seconds=3,
                               evidence_output=EVIDENCE_DIR,
                               existing_task_ids={case.case_id: existing_task_id} if existing_task_id else None,
                               pdf_artifact_output_dir=EVIDENCE_DIR / "pdfs",
                               deepeval_export_dir=DEEPEVAL_EXPORT_DIR)
            if not existing_task_id:
                client.create_task = original_create_task
            item = report["cases"][0]
            completed.append(item)
            completed = ordered_cases(completed, cases)
            active_item = None
            print(json.dumps({"case": index, "total": len(cases), "caseId": case.case_id,
                              "evaluationStatus": item.get("evaluationStatus"),
                              "score": item.get("score"), "taskId": item.get("taskId"),
                              "runId": item.get("runId")}, ensure_ascii=False), flush=True)
            write_progress(completed, suite_hash, suite_started, token)
            if should_stop_after_case(item):
                failure = item.get("observedToolFailure") or {}
                failure_tool = failure.get("tool") or next((
                    assertion.get("expected") for assertion in item.get("assertions", [])
                    if isinstance(assertion, dict) and assertion.get("type") == "required_tool"
                    and assertion.get("passed") is False), "unknown")
                infrastructure_error = (str(item.get("error")) if item.get("error") else
                    f"Stopping after systemic MCP tool failure: {failure_tool} "
                    f"({failure.get('outcome', 'TOOL_EXECUTION_FAILED')})")
                print(infrastructure_error, file=sys.stderr, flush=True)
                break
    except EvalTransportError as exc:
        infrastructure_error = str(exc)
        print(f"L5 transport error; stopping to avoid repeated load: {infrastructure_error}", file=sys.stderr)
    finally:
        write_progress(ordered_cases(completed + ([active_item] if active_item else []), cases),
                       suite_hash, suite_started, token, infrastructure_error)
        client.token = ""
        del token
    return 0 if len(completed) == len(cases) and all(item.get("passed") for item in completed) else 1


def write_progress(cases, suite_hash, started, token, error=None):
    from runpy import run_path
    l5_runner = run_path(str(ROOT / "scripts/run-l5-agentteams-eval.py"))
    report = {
        "schemaVersion": "evaluation-report-v1", "suite": "dual-mcp-10-case-l5",
        "evaluatorVersion": "deterministic-v1", "suiteVersion": suite_hash,
        "startedAt": started, "finishedAt": datetime.now(timezone.utc).isoformat(),
        "targetScope": {"tenant": "tenant-a", "project": "project-a", "team": "team-a"},
        "passed": len(cases) == 10 and all(item.get("passed") for item in cases),
        "taskSuccess": len(cases) == 10 and all(item.get("taskSuccess") for item in cases),
        "evaluationStatus": ("INCOMPLETE" if error or len(cases) != 10 or
                             any(item.get("evaluationStatus") == "RUNNING" for item in cases) else
                             "PASSED" if all(item.get("passed") for item in cases) else "FAILED"),
        "completedCaseCount": len(cases), "expectedCaseCount": 10,
        "cases": cases,
    }
    if error:
        report["infrastructureError"] = error
    l5_runner["write_report"](REPORT, report, token)
    print(f"Progress report saved: {REPORT}", file=sys.stderr, flush=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except EvalError as exc:
        print(f"L5 evaluation error: {exc}", file=sys.stderr)
        raise SystemExit(2)
