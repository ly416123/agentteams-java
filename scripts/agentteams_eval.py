#!/usr/bin/env python3
"""Deterministic evaluation runner for the AgentTeams Task API.

The runner intentionally keeps the evaluation contract outside the production
runtime.  Cases are versioned YAML, while the report contains only task/run
identifiers and assertion results.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import yaml


TERMINAL_PHASES = {"SUCCEEDED", "FAILED", "CANCELLED"}


class EvalError(RuntimeError):
    """A safe, user-facing evaluation error without credentials."""


class EvalTransportError(EvalError):
    """The API could not be called or returned an unusable response."""


@dataclass(frozen=True)
class EvalCase:
    case_id: str
    name: str
    task: dict[str, Any]
    assertions: list[dict[str, Any]]
    limits: dict[str, Any]


@dataclass(frozen=True)
class AssertionResult:
    assertion_type: str
    passed: bool
    message: str
    expected: Any = None
    actual: Any = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "type": self.assertion_type,
            "passed": self.passed,
            "message": self.message,
            "expected": self.expected,
            "actual": self.actual,
        }


def _mapping(value: Any, context: str) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        raise EvalError(f"{context} must be an object")
    return dict(value)


def load_cases(path: str | Path) -> list[EvalCase]:
    """Load and validate a YAML suite."""
    try:
        raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    except OSError as exc:
        raise EvalError(f"cannot read cases file {path}: {exc}") from exc
    except yaml.YAMLError as exc:
        raise EvalError(f"invalid YAML in cases file {path}: {exc}") from exc

    entries = raw.get("cases") if isinstance(raw, Mapping) else raw
    if not isinstance(entries, list) or not entries:
        raise EvalError("cases file must contain a non-empty cases list")
    cases: list[EvalCase] = []
    seen: set[str] = set()
    for index, item in enumerate(entries):
        value = _mapping(item, f"case[{index}]")
        case_id = str(value.get("id", "")).strip()
        if not case_id:
            raise EvalError(f"case[{index}] requires id")
        if case_id in seen:
            raise EvalError(f"duplicate case id: {case_id}")
        seen.add(case_id)
        task = _mapping(value.get("task", {}), f"case {case_id}.task")
        assertions = value.get("assertions", [])
        if not isinstance(assertions, list) or not assertions:
            raise EvalError(f"case {case_id} requires a non-empty assertions list")
        normalized_assertions = [_mapping(a, f"case {case_id}.assertion[{i}]")
                                 for i, a in enumerate(assertions)]
        limits = _mapping(value.get("limits", {}), f"case {case_id}.limits")
        cases.append(EvalCase(case_id, str(value.get("name", case_id)), task,
                              normalized_assertions, limits))
    return cases


def _payload(event: Mapping[str, Any]) -> dict[str, Any]:
    value = event.get("payload", event.get("data", {}))
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return {}
    return dict(value) if isinstance(value, Mapping) else {}


def _tool_names(observation: Mapping[str, Any]) -> set[str]:
    names: set[str] = set()
    for event in observation.get("events", []) or []:
        if not isinstance(event, Mapping):
            continue
        event_type = str(event.get("eventType", event.get("type", "")))
        if event_type not in {"tool.called", "tool.finished", "tool.completed"}:
            continue
        payload = _payload(event)
        name = payload.get("tool") or payload.get("toolName") or payload.get("name")
        if name:
            names.add(str(name))
    return names


def _output_text(observation: Mapping[str, Any]) -> str:
    result = observation.get("result") or {}
    values: list[str] = []
    if isinstance(result, Mapping):
        for key in ("summary", "output", "content"):
            value = result.get(key)
            if value is not None:
                values.append(str(value))
        for artifact in result.get("artifacts", []) or []:
            if isinstance(artifact, Mapping) and artifact.get("content") is not None:
                values.append(str(artifact["content"]))
    return "\n".join(values)


def _safe_artifact_metadata(artifact: Mapping[str, Any]) -> dict[str, Any]:
    """Keep report metadata free of presigned URLs and downloaded content."""
    return {key: artifact.get(key) for key in (
        "name", "storageRef", "contentType", "sizeBytes", "sha256", "version", "stage", "visibility"
    ) if key in artifact}


def _artifact_check(observation: Mapping[str, Any]) -> tuple[bool, str, list[dict[str, Any]]]:
    result = observation.get("result") or {}
    artifacts = result.get("artifacts", []) if isinstance(result, Mapping) else []
    if not isinstance(artifacts, list) or not artifacts:
        return False, "no result artifacts were returned", []
    bad: list[str] = []
    for artifact in artifacts:
        if not isinstance(artifact, Mapping):
            bad.append("non-object artifact")
            continue
        ref = str(artifact.get("storageRef", ""))
        size = artifact.get("sizeBytes", 0)
        digest = str(artifact.get("sha256", ""))
        if not ref.startswith("tasks/"):
            bad.append(f"{artifact.get('name', '<unnamed>')}: storageRef is not durable")
        if not isinstance(size, (int, float)) or size <= 0:
            bad.append(f"{artifact.get('name', '<unnamed>')}: sizeBytes must be positive")
        if len(digest) != 64 or any(ch not in "0123456789abcdef" for ch in digest.lower()):
            bad.append(f"{artifact.get('name', '<unnamed>')}: invalid sha256")
    return not bad, "; ".join(bad) if bad else "all artifacts have durable metadata", [
        _safe_artifact_metadata(artifact) for artifact in artifacts if isinstance(artifact, Mapping)
    ]


def evaluate_observation(case: EvalCase, observation: Mapping[str, Any]) -> list[AssertionResult]:
    """Evaluate only deterministic assertions; never invokes an LLM judge."""
    results: list[AssertionResult] = []
    task = observation.get("task") or {}
    tools = _tool_names(observation)
    for assertion in case.assertions:
        assertion_type = str(assertion.get("type", ""))
        if assertion_type == "phase":
            expected = str(assertion.get("expected", ""))
            actual = task.get("phase")
            results.append(AssertionResult(assertion_type, actual == expected,
                                           f"phase is {actual!s}", expected, actual))
        elif assertion_type == "required_tool":
            expected = str(assertion.get("name", ""))
            passed = expected in tools
            results.append(AssertionResult(assertion_type, passed,
                                           f"required tool {expected} was {'called' if passed else 'not called'}",
                                           expected, sorted(tools)))
        elif assertion_type == "forbidden_tool":
            expected = str(assertion.get("name", ""))
            passed = expected not in tools
            results.append(AssertionResult(assertion_type, passed,
                                           f"forbidden tool {expected} was {'not called' if passed else 'called'}",
                                           expected, sorted(tools)))
        elif assertion_type == "output_contains":
            expected = str(assertion.get("value", ""))
            actual = _output_text(observation)
            passed = expected in actual
            results.append(AssertionResult(assertion_type, passed,
                                           f"output {'contains' if passed else 'does not contain'} expected text",
                                           expected, actual[:500]))
        elif assertion_type == "artifact_integrity":
            passed, message, artifacts = _artifact_check(observation)
            results.append(AssertionResult(assertion_type, passed, message,
                                           "durable metadata", artifacts))
        else:
            results.append(AssertionResult(assertion_type, False,
                                           f"unknown assertion type: {assertion_type}"))
    return results


class AgentTeamsClient:
    """Small urllib client; transport can be injected for unit tests."""

    def __init__(self, base_url: str, token: str, timeout: float = 30.0,
                 transport: Callable[..., tuple[int, Mapping[str, str], bytes]] | None = None):
        if not token or token.strip() != token:
            raise EvalError("evaluation token is missing or has surrounding whitespace")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout
        self.transport = transport or self._urllib_transport

    def _urllib_transport(self, request: Request, timeout: float) -> tuple[int, Mapping[str, str], bytes]:
        try:
            with urlopen(request, timeout=timeout) as response:
                return response.status, response.headers, response.read()
        except HTTPError as exc:
            return exc.code, exc.headers, exc.read()
        except URLError as exc:
            raise EvalTransportError(
                f"request failed for {request.method} {request.full_url}: {exc.reason}"
            ) from exc

    def _request(self, method: str, path: str, body: Mapping[str, Any] | None = None,
                 idempotent: bool = False) -> Any:
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/json"}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        if idempotent:
            headers["Idempotency-Key"] = str(uuid.uuid4())
        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        status, _, raw = self.transport(request, self.timeout)
        if status < 200 or status >= 300:
            detail = raw.decode("utf-8", errors="replace")[:300]
            detail = detail.replace(self.token, "[REDACTED]")
            raise EvalTransportError(f"HTTP {status} {method} {path}: {detail}")
        if not raw:
            return None
        try:
            return json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise EvalTransportError(f"invalid JSON response from {method} {path}") from exc

    def create_task(self, case: EvalCase) -> dict[str, Any]:
        task = case.task
        spec = task.get("spec")
        if not isinstance(spec, Mapping):
            scope = _mapping(task.get("scope", {}), f"case {case.case_id}.task.scope")
            input_json = task.get("inputJson", {"prompt": task.get("prompt", "")})
            spec = {"scope": scope, "taskType": task.get("taskType", "qwenpaw"),
                    "inputJson": input_json,
                    "requiredCapabilities": task.get("requiredCapabilities", [])}
        body = {"title": task.get("title", case.name),
                "description": task.get("description", "AgentTeams evaluation"),
                "spec": spec, "taskType": task.get("taskType", "qwenpaw"),
                "source": "agentteams-eval"}
        value = self._request("POST", "/api/v1/tasks", body, idempotent=True)
        return _mapping(value, "create task response")

    def queue_task(self, task_id: str, expected_version: int = 0) -> dict[str, Any]:
        value = self._request("POST", f"/api/v1/tasks/{task_id}/queue",
                              {"expectedVersion": expected_version}, idempotent=True)
        return _mapping(value, "queue task response")

    def get_task(self, task_id: str) -> dict[str, Any]:
        return _mapping(self._request("GET", f"/api/v1/tasks/{task_id}"), "task response")

    def wait_for_terminal(self, task_id: str, timeout_seconds: float, poll_seconds: float) -> dict[str, Any]:
        deadline = time.monotonic() + timeout_seconds
        while True:
            task = self.get_task(task_id)
            if str(task.get("phase", "")) in TERMINAL_PHASES:
                return task
            if time.monotonic() >= deadline:
                raise EvalError(f"task {task_id} did not reach terminal phase within {timeout_seconds:g}s")
            time.sleep(max(0.0, poll_seconds))

    def get_run(self, task_id: str) -> dict[str, Any]:
        value = self._request("GET", f"/api/v1/tasks/{task_id}/runs")
        runs = value if isinstance(value, list) else (value or {}).get("runs", [])
        if not runs:
            raise EvalError(f"task {task_id} has no execution run")
        return _mapping(runs[-1], "run response")

    def get_observation(self, task_id: str, run_id: str) -> dict[str, Any]:
        events = self._request("GET", f"/api/v1/tasks/{task_id}/runs/{run_id}/process-events")
        result = self._request("GET", f"/api/v1/tasks/{task_id}/runs/{run_id}/result")
        if isinstance(result, Mapping) and isinstance(result.get("artifacts"), list):
            enriched = []
            for artifact in result["artifacts"]:
                if not isinstance(artifact, Mapping):
                    enriched.append(artifact)
                    continue
                copy = dict(artifact)
                download_url = copy.get("downloadUrl")
                if download_url:
                    try:
                        request = Request(str(download_url), headers={"Accept": "application/octet-stream"})
                        with urlopen(request, timeout=self.timeout) as response:
                            copy["content"] = response.read(2 * 1024 * 1024).decode("utf-8", errors="replace")
                    except (OSError, URLError):
                        # Artifact metadata assertions remain useful if content download is unavailable.
                        pass
                enriched.append(copy)
            result = dict(result)
            result["artifacts"] = enriched
        return {"task": self.get_task(task_id), "run": {"id": run_id},
                "events": events if isinstance(events, list) else [], "result": result or {}}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _redact(value: Any, secret: str) -> Any:
    if isinstance(value, str):
        return value.replace(secret, "[REDACTED]")
    if isinstance(value, list):
        return [_redact(item, secret) for item in value]
    if isinstance(value, dict):
        return {key: _redact(item, secret) for key, item in value.items()}
    return value


def _write_deepeval_export(case: EvalCase, observation: Mapping[str, Any], directory: Path) -> None:
    """Write an optional, credential-free semantic-evaluation input."""
    try:
        # Import lazily so the deterministic Runner never depends on DeepEval.
        from eval.deepeval_adapter import render_export
    except ImportError as exc:
        raise EvalError(f"cannot load DeepEval export adapter: {exc}") from exc
    try:
        directory.mkdir(parents=True, exist_ok=True)
        target = directory / f"{case.case_id}.json"
        target.write_text(json.dumps(render_export(case, observation), ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8")
    except OSError as exc:
        raise EvalError(f"cannot write DeepEval export for {case.case_id}: {exc}") from exc


def run_suite(client: AgentTeamsClient, cases: list[EvalCase], timeout_seconds: float,
              poll_seconds: float, deepeval_export_dir: Path | None = None) -> dict[str, Any]:
    started = _now()
    report_cases: list[dict[str, Any]] = []
    for case in cases:
        item: dict[str, Any] = {"caseId": case.case_id, "passed": False, "score": 0.0, "assertions": []}
        try:
            created = client.create_task(case)
            task_id = str(created.get("id", ""))
            if not task_id:
                raise EvalError("create task response has no task id")
            item["taskId"] = task_id
            client.queue_task(task_id, int(created.get("version", 0)))
            client.wait_for_terminal(task_id, timeout_seconds, poll_seconds)
            run = client.get_run(task_id)
            run_id = str(run.get("id") or run.get("runId") or "")
            if not run_id:
                raise EvalError(f"task {task_id} run response has no run id")
            item["runId"] = run_id
            observation = client.get_observation(task_id, run_id)
            if deepeval_export_dir is not None:
                _write_deepeval_export(case, observation, deepeval_export_dir)
            assertion_results = evaluate_observation(case, observation)
            item["assertions"] = [_redact(result.as_dict(), client.token) for result in assertion_results]
            item["score"] = (sum(result.passed for result in assertion_results) / len(assertion_results)
                              if assertion_results else 0.0)
            item["passed"] = bool(assertion_results) and all(result.passed for result in assertion_results)
        except EvalTransportError:
            raise
        except EvalError as exc:
            item["error"] = _redact(str(exc), client.token)
        report_cases.append(item)
    return {"suite": "core", "startedAt": started, "finishedAt": _now(),
            "passed": all(item["passed"] for item in report_cases), "cases": report_cases}


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--token-env", default="AGENTTEAMS_EVAL_TOKEN")
    parser.add_argument("--cases", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--deepeval-export-dir", type=Path,
                        help="optional directory for credential-free DeepEval case exports")
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    parser.add_argument("--poll-seconds", type=float, default=3.0)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    try:
        token = os.environ.get(args.token_env, "")
        if not token:
            raise EvalError(f"environment variable {args.token_env} is not set")
        cases = load_cases(args.cases)
        report = run_suite(AgentTeamsClient(args.base_url, token), cases,
                           args.timeout_seconds, args.poll_seconds,
                           deepeval_export_dir=args.deepeval_export_dir)
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return 0 if report["passed"] else 1
    except (EvalError, OSError, ValueError) as exc:
        print(f"evaluation configuration or network error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
