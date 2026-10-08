#!/usr/bin/env python3
"""Deterministic evaluation runner for the AgentTeams Task API.

The runner intentionally keeps the evaluation contract outside the production
runtime.  Cases are versioned YAML, while the report contains only task/run
identifiers and assertion results.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from urllib.parse import urlsplit

import yaml


# Keep the optional ``eval`` package importable when this module is launched as
# ``python3 scripts/agentteams_eval.py``. Python puts ``scripts/`` (rather than
# the repository root) first on sys.path for that invocation.
REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))


TERMINAL_PHASES = {"SUCCEEDED", "FAILED", "CANCELLED"}
PROCESS_EVENT_PAGE_SIZE = 100
PROCESS_EVENT_LIMIT = 1000
SENSITIVE_KEY_PARTS = ("token", "secret", "password", "cookie", "authorization", "apikey", "credential", "privatekey")


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
    passed: bool | None
    message: str
    expected: Any = None
    actual: Any = None
    evidence_refs: tuple[str, ...] = ()

    @property
    def status(self) -> str:
        return "INCONCLUSIVE" if self.passed is None else "PASSED" if self.passed else "FAILED"

    def as_dict(self) -> dict[str, Any]:
        return {
            "type": self.assertion_type,
            "passed": self.passed,
            "status": self.status,
            "message": self.message,
            "expected": self.expected,
            "actual": self.actual,
            "evidenceRefs": list(self.evidence_refs),
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
    metadata = {key: artifact.get(key) for key in (
        "name", "storageRef", "contentType", "sizeBytes", "sha256", "version", "stage", "visibility"
    ) if key in artifact and isinstance(artifact.get(key), (str, int, float, bool, type(None)))}
    storage_ref = str(metadata.get("storageRef", ""))
    if "?" in storage_ref or "x-amz-" in storage_ref.lower() or not storage_ref.startswith("tasks/"):
        digest = str(metadata.get("sha256", ""))
        metadata["storageRef"] = f"artifact:{digest}" if digest else "artifact:metadata-only"
    return _sanitize_value(metadata)


def _safe_id_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    identifiers = []
    for item in value:
        identifier = item.get("taskId", item.get("id")) if isinstance(item, Mapping) else item
        if isinstance(identifier, (str, int)) and str(identifier):
            identifiers.append(str(identifier))
    return identifiers


def _sanitize_value(value: Any) -> Any:
    if isinstance(value, Mapping):
        sanitized: dict[str, Any] = {}
        for key, item in value.items():
            normalized_key = re.sub(r"[^a-z0-9]", "", str(key).lower())
            if any(part in normalized_key for part in SENSITIVE_KEY_PARTS):
                continue
            sanitized[str(key)] = _sanitize_value(item)
        return sanitized
    if isinstance(value, list):
        return [_sanitize_value(item) for item in value]
    if isinstance(value, str) and ("x-amz-" in value.lower() or "?signature=" in value.lower()):
        return "[REDACTED_URL]"
    return value


def _sanitize_event(event: Mapping[str, Any]) -> dict[str, Any]:
    safe = {key: event.get(key) for key in (
        "eventId", "taskId", "runId", "sequence", "eventType", "visibility", "occurredAt", "correlationId"
    ) if key in event}
    payload = event.get("payload", event.get("data"))
    payload_ref = event.get("payloadRef")
    if payload is not None:
        parsed = _payload(event)
        safe_payload = {key: parsed[key] for key in ("tool", "toolName", "name", "sequence", "ok", "status", "callId")
                        if key in parsed and isinstance(parsed[key], (str, int, bool))}
        safe["payload"] = safe_payload or None
        if not safe_payload:
            safe["payloadRef"] = f"task-process:{event.get('eventId', 'unknown')}"
    else:
        safe["payload"] = None
        if payload_ref:
            safe["payloadRef"] = f"task-process:{event.get('eventId', 'unknown')}"
    return safe


def build_evidence_bundle(task: Mapping[str, Any], run: Mapping[str, Any], events: list[Any],
                          tree: list[Any] | None, decisions: list[Any] | None,
                          result: Mapping[str, Any] | None,
                          evidence_completeness: Mapping[str, Any] | None = None,
                          event_completeness: str | None = None) -> dict[str, Any]:
    """Normalize an observation into a credential-free, metadata-first Evidence Bundle."""
    task_value = {key: _sanitize_value(task[key]) for key in ("id", "phase", "taskType") if key in task}
    run_value = {key: _sanitize_value(run[key]) for key in ("id", "runId", "status") if key in run}
    observed_tool_names = sorted(_tool_names({"events": events}))
    safe_events = [_sanitize_event(event) for event in events if isinstance(event, Mapping)][:PROCESS_EVENT_LIMIT]
    completeness = dict(evidence_completeness or (result.get("evidenceCompleteness", {}) if isinstance(result, Mapping) else {}) or {})
    completeness_status = str(completeness.get("status", "UNKNOWN")).upper()
    event_completeness = str(event_completeness or completeness_status).upper()
    if event_completeness not in {"COMPLETE", "TRUNCATED", "INCONCLUSIVE"}:
        event_completeness = "INCONCLUSIVE"
    run_id = run_value.get("id") or run_value.get("runId") if isinstance(run_value, Mapping) else None
    raw_result = dict(result) if isinstance(result, Mapping) else None
    safe_result = None
    artifact_refs: list[dict[str, Any]] = []
    if raw_result is not None:
        safe_result = {"status": _sanitize_value(raw_result.get("status"))} if "status" in raw_result else {}
        raw_artifacts = raw_result.get("artifacts", [])
        artifact_refs = [_safe_artifact_metadata(item) for item in raw_artifacts if isinstance(item, Mapping)]
        safe_result["artifacts"] = artifact_refs
    task_id = task_value.get("id") if isinstance(task_value, Mapping) else None
    return {
        "schemaVersion": "evidence-bundle-v1",
        "taskId": task_id,
        "executionRunId": run_id,
        "taskSnapshot": task_value,
        "taskEvents": safe_events,
        "processEvents": safe_events,
        "taskTree": [{**{key: _sanitize_value(node[key]) for key in ("taskId", "parentTaskId", "status")
                       if key in node}, **({"dependencyIds": _safe_id_list(node.get("dependencyIds"))}
                                             if "dependencyIds" in node else {})}
                      for node in (tree or []) if isinstance(node, Mapping)],
        "decisions": [{key: _sanitize_value(node[key]) for key in ("id", "taskId") if key in node}
                      for node in (decisions or []) if isinstance(node, Mapping)],
        "toolCallSummaries": [{"name": name} for name in observed_tool_names],
        "resultManifest": safe_result,
        "artifactRefs": artifact_refs,
        "taskPhase": task_value.get("phase") if isinstance(task_value, Mapping) else None,
        "runStatus": run_value.get("status") if isinstance(run_value, Mapping) else None,
        "manifestStatus": safe_result.get("status") if isinstance(safe_result, Mapping) else None,
        "evidenceCompleteness": completeness,
        "eventCompleteness": event_completeness,
        "approvalHistory": [],
        "agentMetadata": {},
        "policyOutcomes": [],
        "inputStatus": "COMPLETE" if safe_result is not None and event_completeness == "COMPLETE"
        and isinstance(task_id, str) and bool(task_id) and bool(run_id) else "INCONCLUSIVE",
    }


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


def _event_type(event: Mapping[str, Any]) -> str:
    return str(event.get("eventType", event.get("type", "")))


def _tool_name(event: Mapping[str, Any]) -> str | None:
    payload = _payload(event)
    name = payload.get("tool") or payload.get("toolName") or payload.get("name")
    return str(name).strip() if name else None


def _called_tools(events: list[Mapping[str, Any]]) -> list[str] | None:
    calls: list[tuple[int, int, str]] = []
    for index, event in enumerate(events):
        if _event_type(event) != "tool.called":
            continue
        name = _tool_name(event)
        if not name:
            return None
        sequence = event.get("sequence", _payload(event).get("sequence"))
        if not isinstance(sequence, int):
            return None
        calls.append((sequence, index, name))
    return [name for _, _, name in sorted(calls)]


def _tool_lifecycle(events: list[Mapping[str, Any]], name: str) -> tuple[list[Mapping[str, Any]], list[bool]] | None:
    calls: list[tuple[int, Mapping[str, Any], Any]] = []
    outcomes: list[tuple[int, bool, Any]] = []
    for event in events:
        event_type = _event_type(event)
        if event_type not in {"tool.called", "tool.finished", "tool.completed"}:
            continue
        tool_name = _tool_name(event)
        payload = _payload(event)
        if not tool_name:
            return None
        if tool_name != name:
            continue
        sequence = event.get("sequence", payload.get("sequence"))
        if not isinstance(sequence, int):
            return None
        call_id = payload.get("callId")
        if event_type == "tool.called":
            calls.append((sequence, event, call_id))
        else:
            ok = payload.get("ok")
            if not isinstance(ok, bool):
                status = str(payload.get("status", "")).upper()
                if status not in {"SUCCEEDED", "FAILED"}:
                    return None
                ok = status == "SUCCEEDED"
            outcomes.append((sequence, ok, call_id))
    calls.sort(key=lambda value: value[0])
    outcomes.sort(key=lambda value: value[0])
    if any(not isinstance(call_id, str) or not call_id for _, _, call_id in calls + outcomes):
        return None
    call_ids = [call_id for _, _, call_id in calls]
    outcome_ids = [call_id for _, _, call_id in outcomes]
    if len(set(call_ids)) != len(call_ids) or len(set(outcome_ids)) != len(outcome_ids):
        return None
    if set(call_ids) != set(outcome_ids) or len(call_ids) != len(outcome_ids):
        return None
    # Validate each target ID against the complete stream before accepting a
    # pair. Filtering by tool name first would hide collisions with other tools.
    for call_id in call_ids:
        correlated = []
        for event in events:
            if _event_type(event) not in {"tool.called", "tool.finished", "tool.completed"}:
                continue
            payload = _payload(event)
            if payload.get("callId") == call_id:
                correlated.append(event)
        if (len(correlated) != 2
                or sum(_event_type(event) == "tool.called" for event in correlated) != 1
                or sum(_event_type(event) in {"tool.finished", "tool.completed"} for event in correlated) != 1
                or any(_tool_name(event) != name for event in correlated)):
            return None
    outcome_by_id = {call_id: ok for _, ok, call_id in outcomes}
    return [event for _, event, _ in calls], [outcome_by_id[call_id] for _, _, call_id in calls]


def _has_observed_failed_tool(events: list[Mapping[str, Any]], name: str) -> bool:
    for event in events:
        if _event_type(event) not in {"tool.finished", "tool.completed"} or _tool_name(event) != name:
            continue
        payload = _payload(event)
        if payload.get("ok") is not False and str(payload.get("status", "")).upper() != "FAILED":
            continue
        call_id = payload.get("callId")
        if not isinstance(call_id, str) or not call_id:
            continue
        matching_calls = []
        matching_completions = []
        for candidate in events:
            candidate_type = _event_type(candidate)
            if candidate_type not in {"tool.called", "tool.finished", "tool.completed"}:
                continue
            candidate_payload = _payload(candidate)
            if candidate_payload.get("callId") != call_id:
                continue
            if candidate_type == "tool.called":
                matching_calls.append(candidate)
            else:
                matching_completions.append(candidate)
        if (len(matching_calls) == 1 and len(matching_completions) == 1
                and _tool_name(matching_calls[0]) == name
                and _tool_name(matching_completions[0]) == name
                and matching_completions[0] is event):
            return True
    return False


def _event_completeness(observation: Mapping[str, Any]) -> str:
    explicit = observation.get("eventCompleteness")
    if isinstance(explicit, str) and explicit.upper() in {"COMPLETE", "TRUNCATED", "INCONCLUSIVE"}:
        return explicit.upper()
    completeness = observation.get("evidenceCompleteness") or {}
    if isinstance(completeness, Mapping):
        status = str(completeness.get("status", "")).upper()
        if status in {"TRUNCATED", "INCONCLUSIVE", "COMPLETE"}:
            return status
        observed, limit = completeness.get("observedEventCount"), completeness.get("eventLimit")
        if isinstance(observed, int) and isinstance(limit, int) and observed > limit:
            return "TRUNCATED"
    events = observation.get("events", []) or []
    if any(isinstance(event, Mapping) and event.get("truncated") is True for event in events):
        return "TRUNCATED"
    return "INCONCLUSIVE"


def _events_truncated(observation: Mapping[str, Any]) -> bool:
    return _event_completeness(observation) != "COMPLETE"


def _dependency_success(tree: Any, require_dependencies: bool = False) -> tuple[bool | None, Any]:
    if not isinstance(tree, list) or not tree:
        return None, None
    nodes = {str(node.get("taskId", node.get("id", ""))): node for node in tree if isinstance(node, Mapping)}
    saw_dependency = False
    statuses = []
    for node in nodes.values():
        dependency_ids = node.get("dependencyIds", node.get("dependencies", [])) or []
        for dependency in dependency_ids:
            dep_id = str(dependency.get("taskId", dependency.get("id", ""))) if isinstance(dependency, Mapping) else str(dependency)
            target = nodes.get(dep_id)
            if target is None:
                return None, {"missingDependency": dep_id}
            saw_dependency = True
            status = target.get("status", target.get("phase"))
            if status is None:
                return None, {"dependency": dep_id, "status": None}
            statuses.append(str(status))
    if not saw_dependency and require_dependencies:
        return None, {"dependencyCount": 0}
    return True if not saw_dependency or all(status.upper() == "SUCCEEDED" for status in statuses) else False, statuses


def _dependency_handoff(tree: Any) -> tuple[bool | None, Any]:
    if not isinstance(tree, list) or not tree:
        return None, None
    nodes = {str(node.get("taskId", node.get("id", ""))): node for node in tree if isinstance(node, Mapping)}
    references = []
    saw_dependency = False
    for node in nodes.values():
        dependencies = node.get("dependencyIds", node.get("dependencies", [])) or []
        if not dependencies:
            continue
        saw_dependency = True
        refs = node.get("dependencyResults", node.get("dependencyResultRefs", {})) or {}
        for dependency in dependencies:
            dep_id = str(dependency.get("taskId", dependency.get("id", ""))) if isinstance(dependency, Mapping) else str(dependency)
            ref = refs.get(dep_id) if isinstance(refs, Mapping) else None
            if not isinstance(ref, str) or not ref:
                return None, {"missingReferenceFor": dep_id}
            if not re.fullmatch(r"task-result:[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", ref) or ref != f"task-result:{dep_id}":
                return False, {"invalidReferenceFor": dep_id}
            references.append(f"dependency-result:{dep_id}")
    return (True, references) if saw_dependency else (None, None)


def _pdf_check(observation: Mapping[str, Any], assertion: Mapping[str, Any]) -> tuple[bool | None, str, Any]:
    result = observation.get("result")
    if not isinstance(result, Mapping):
        return None, "result manifest is unavailable", None
    artifacts = result.get("artifacts", [])
    if not isinstance(artifacts, list) or not artifacts:
        return False, "PDF artifact is missing", []
    pdfs = [artifact for artifact in artifacts if isinstance(artifact, Mapping)
            and (str(artifact.get("name", "")).lower().endswith(".pdf")
                 or str(artifact.get("contentType", "")).lower() == "application/pdf")]
    if not pdfs:
        return False, "PDF artifact is missing", []
    if assertion.get("exactlyOne") is True and len(pdfs) != 1:
        return False, "exactly one PDF artifact is required", [{"count": len(pdfs)}]
    checked = []
    for artifact in pdfs:
        name = str(artifact.get("name", ""))
        content_type = str(artifact.get("contentType", ""))
        size = artifact.get("sizeBytes")
        digest = str(artifact.get("sha256", "")).lower()
        storage_ref = str(artifact.get("storageRef", ""))
        download_url = str(artifact.get("downloadUrl", ""))
        try:
            parsed_download = urlsplit(download_url)
            download_hostname = parsed_download.hostname
        except ValueError:
            return False, "PDF download URL is malformed", [{"count": len(pdfs)}]
        data = artifact.get("contentBytes")
        invalid_reference = assertion.get("durable") is True and (
            not storage_ref.startswith("tasks/") or "?" in storage_ref or "x-amz-" in storage_ref.lower())
        invalid_download = False
        if assertion.get("downloadable") is True:
            invalid_download = (parsed_download.scheme.lower() not in {"http", "https"} or not download_hostname
                               or bool(parsed_download.username) or bool(parsed_download.password))
        if (not name.lower().endswith(".pdf") or content_type.lower() != "application/pdf"
                or not isinstance(size, int) or size <= 0 or len(digest) != 64
                or any(c not in "0123456789abcdef" for c in digest)
                or invalid_reference or invalid_download):
                return False, "PDF count, MIME, extension, size or SHA-256 metadata is invalid", [{"count": len(pdfs)}]
        if data is None or not isinstance(data, bytes):
            return None, "PDF signature bytes are unavailable; remote downloads are disabled", [{"count": len(pdfs)}]
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            return False, "PDF size or SHA-256 does not match artifact bytes", [{"count": len(pdfs)}]
        if not data.startswith(b"%PDF-") or b"%%EOF" not in data:
            return False, "PDF signature or terminator is invalid", [{"count": len(pdfs)}]
        try:
            from pypdf import PdfReader
            page_count = len(PdfReader(io.BytesIO(data), strict=True).pages)
        except ImportError:
            return None, "pypdf is unavailable to parse the PDF page tree", [{"count": len(pdfs)}]
        except Exception:
            return False, "PDF page tree could not be parsed", [{"count": len(pdfs)}]
        if page_count < 1:
            return False, "PDF contains no pages", [{"count": len(pdfs)}]
        checked.append({"pageCount": page_count})
    return True, "PDF metadata, signature, digest and page tree are valid", checked


def evaluate_observation(case: EvalCase, observation: Mapping[str, Any]) -> list[AssertionResult]:
    """Evaluate only deterministic assertions; never invokes an LLM judge."""
    results: list[AssertionResult] = []
    task = observation.get("task") or {}
    run_id = (observation.get("run") or {}).get("id") or (observation.get("run") or {}).get("runId") or "unknown"
    events_ref = f"task-events:{run_id}"
    tree_ref = f"task-tree:{run_id}"
    manifest_ref = f"result-manifest:{run_id}"
    events = [event for event in observation.get("events", []) or [] if isinstance(event, Mapping)]
    truncated = _events_truncated(observation)
    for assertion in case.assertions:
        assertion_type = str(assertion.get("type", ""))
        if assertion_type == "phase":
            expected = str(assertion.get("expected", ""))
            actual = task.get("phase")
            results.append(AssertionResult(assertion_type, actual == expected if actual is not None else None,
                                           f"phase is {actual!s}", expected, actual,
                                           (f"task-snapshot:{(task or {}).get('id', 'unknown')}",)))
        elif assertion_type == "required_tool":
            expected = str(assertion.get("name", ""))
            lifecycle = _tool_lifecycle(events, expected)
            if truncated or lifecycle is None:
                passed = None
                message = "tool lifecycle evidence is incomplete or unparseable"
            elif not lifecycle[0] and lifecycle[1]:
                passed, message = None, "tool completion is present without a matching call event"
            elif not lifecycle[0]:
                passed, message = False, f"required tool {expected} was not called"
            elif len(lifecycle[1]) < len(lifecycle[0]):
                passed, message = None, f"required tool {expected} completion outcome is unavailable"
            elif any(outcome is True for outcome in lifecycle[1]):
                passed, message = True, f"required tool {expected} completed successfully"
            else:
                passed, message = False, f"required tool {expected} failed"
            results.append(AssertionResult(assertion_type, passed, message, expected,
                                           {"calls": len(lifecycle[0]), "outcomes": lifecycle[1]} if lifecycle else None,
                                           (events_ref,)))
        elif assertion_type == "forbidden_tool":
            expected = str(assertion.get("name", ""))
            names = _called_tools(events)
            passed = None if truncated or names is None else expected not in names
            results.append(AssertionResult(assertion_type, passed,
                                           f"forbidden tool {expected} evidence status is {AssertionResult(assertion_type, passed, '').status}",
                                           expected, sorted(names) if names is not None else None, (events_ref,)))
        elif assertion_type == "output_contains":
            expected = str(assertion.get("value", ""))
            actual = _output_text(observation)
            passed = None if observation.get("result") is None else expected in actual
            results.append(AssertionResult(assertion_type, passed,
                                           f"output status is {AssertionResult(assertion_type, passed, '').status.lower()}",
                                           "configured output substring", bool(passed) if passed is not None else None,
                                           (manifest_ref,)))
        elif assertion_type == "failed_tool":
            expected = str(assertion.get("name", ""))
            lifecycle = _tool_lifecycle(events, expected)
            if _has_observed_failed_tool(events, expected):
                passed = False
            elif truncated or lifecycle is None or (not lifecycle[0] and lifecycle[1]):
                passed = None
            elif not lifecycle[0]:
                passed = True
            elif not lifecycle[1]:
                passed = None
            else:
                failed = any(outcome is False for outcome in lifecycle[1])
                passed = None if not failed and len(lifecycle[1]) < len(lifecycle[0]) else not failed
            results.append(AssertionResult(assertion_type, passed, "failed tool lifecycle evaluated", expected,
                                           lifecycle[1] if lifecycle else None, (events_ref,)))
        elif assertion_type == "tool_order":
            names = _called_tools(events)
            expected = assertion.get("names", [])
            passed = None if truncated or names is None else names == expected
            results.append(AssertionResult(assertion_type, passed, "tool call order evaluated", expected, names, (events_ref,)))
        elif assertion_type == "tool_count":
            expected_name, expected_count = str(assertion.get("name", "")), assertion.get("expected")
            names = _called_tools(events)
            actual = names.count(expected_name) if names is not None else None
            passed = None if truncated or names is None else actual == expected_count
            results.append(AssertionResult(assertion_type, passed, "tool call count evaluated", expected_count, actual, (events_ref,)))
        elif assertion_type == "dependency_success":
            passed, actual = _dependency_success(observation.get("taskTree"), assertion.get("requireDependencies") is True)
            results.append(AssertionResult(assertion_type, passed,
                                           "dependency statuses evaluated", "all dependencies SUCCEEDED", actual, (tree_ref,)))
        elif assertion_type == "dependency_handoff":
            passed, actual = _dependency_handoff(observation.get("taskTree"))
            results.append(AssertionResult(assertion_type, passed,
                                           "dependency result references evaluated", "result reference for every dependency", actual, (tree_ref,)))
        elif assertion_type == "retry_limit":
            results.append(AssertionResult(assertion_type, None, "retry attempt evidence is unavailable",
                                           assertion.get("maxAttempts"), None, (f"task-run:{run_id}",)))
        elif assertion_type == "run_status":
            run = observation.get("run") or {}
            actual, expected = run.get("status"), str(assertion.get("expected", ""))
            results.append(AssertionResult(assertion_type, None if actual is None else str(actual).upper() == expected.upper(),
                                           "run status evaluated", expected, actual, (f"task-run:{run_id}",)))
        elif assertion_type == "artifact_integrity":
            passed, message, artifacts = _artifact_check(observation)
            if observation.get("result") is None:
                passed, message = None, "result manifest is unavailable"
            results.append(AssertionResult(assertion_type, passed, message,
                                           "durable metadata", artifacts, (manifest_ref,)))
        elif assertion_type == "artifact_pdf":
            passed, message, details = _pdf_check(observation, assertion)
            results.append(AssertionResult(assertion_type, passed, message,
                                           "valid PDF MIME, extension, size, SHA-256 and signature", details, (manifest_ref,)))
        else:
            results.append(AssertionResult(assertion_type, None,
                                           f"unsupported deterministic assertion: {assertion_type}",
                                           evidence_refs=(f"evaluation:{assertion_type or 'unknown'}",)))
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
            spec = {"scope": scope, "taskType": task.get("taskType", "agentscope"),
                    "inputJson": input_json,
                    "requiredCapabilities": task.get("requiredCapabilities", [])}
        body = {"title": task.get("title", case.name),
                "description": task.get("description", "AgentTeams evaluation"),
                "spec": spec, "taskType": task.get("taskType", "agentscope"),
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
        events: list[Any] = []
        after = 0
        event_status = "COMPLETE"
        while len(events) <= PROCESS_EVENT_LIMIT:
            suffix = "" if after == 0 else f"?after={after}"
            page = self._request("GET", f"/api/v1/tasks/{task_id}/runs/{run_id}/process-events{suffix}")
            if isinstance(page, Mapping):
                page_values = page.get("events", [])
                upstream = page.get("evidenceCompleteness") or {}
                if isinstance(upstream, Mapping) and str(upstream.get("status", "")).upper() == "TRUNCATED":
                    event_status = "TRUNCATED"
            else:
                page_values = page
            if not isinstance(page_values, list):
                event_status = "INCONCLUSIVE"
                break
            events.extend(page_values)
            if len(events) > PROCESS_EVENT_LIMIT:
                event_status = "TRUNCATED"
                events = events[:PROCESS_EVENT_LIMIT + 1]
                break
            if len(page_values) < PROCESS_EVENT_PAGE_SIZE:
                break
            sequences = [event.get("sequence") for event in page_values if isinstance(event, Mapping)]
            if not sequences or not all(isinstance(sequence, int) for sequence in sequences):
                event_status = "INCONCLUSIVE"
                break
            next_after = max(sequences)
            if next_after <= after:
                event_status = "INCONCLUSIVE"
                break
            after = next_after
        result = self._request("GET", f"/api/v1/tasks/{task_id}/runs/{run_id}/result")
        task_tree = self._request("GET", f"/api/v1/tasks/{task_id}/runs/{run_id}/tree")
        decisions = self._request("GET", f"/api/v1/tasks/{task_id}/runs/{run_id}/decisions")
        if len(events) > PROCESS_EVENT_LIMIT:
            event_status = "TRUNCATED"
        completeness = {"status": event_status, "observedEventCount": min(len(events), PROCESS_EVENT_LIMIT + 1),
                        "eventLimit": PROCESS_EVENT_LIMIT}
        return {"task": self.get_task(task_id), "run": {"id": run_id},
                "events": events, "evidenceCompleteness": completeness, "eventCompleteness": event_status,
                "result": result,
                "taskTree": task_tree if isinstance(task_tree, list) else None,
                "decisions": decisions if isinstance(decisions, list) else None}


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
              poll_seconds: float, deepeval_export_dir: Path | None = None,
              evaluation_run_id: str | None = None, evidence_output: Path | None = None) -> dict[str, Any]:
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
            run_snapshot = dict(run)
            run_snapshot.update(observation.get("run") or {})
            observation["run"] = run_snapshot
            completeness = dict(observation.get("evidenceCompleteness") or {})
            if completeness.get("status") != "TRUNCATED" and (
                not run_snapshot.get("status") or observation.get("result") is None
            ):
                completeness["status"] = "INCONCLUSIVE"
            observation["evidenceCompleteness"] = completeness
            evidence = build_evidence_bundle(observation.get("task") or {}, observation.get("run") or run,
                                             observation.get("events") or [], observation.get("taskTree"),
                                             observation.get("decisions"), observation.get("result"),
                                             observation.get("evidenceCompleteness"),
                                             observation.get("eventCompleteness"))
            item["evidenceBundle"] = evidence
            task_snapshot = observation.get("task") or {}
            run_snapshot = observation.get("run") or run
            result_manifest = observation.get("result") or {}
            item["taskPhase"] = task_snapshot.get("phase")
            item["taskSuccess"] = str(item["taskPhase"] or "").upper() == "SUCCEEDED"
            item["runStatus"] = run_snapshot.get("status")
            item["manifestStatus"] = result_manifest.get("status") if isinstance(result_manifest, Mapping) else None
            item["evidenceCompleteness"] = evidence["evidenceCompleteness"]
            item["eventCompleteness"] = evidence["eventCompleteness"]
            if evaluation_run_id:
                item["evaluationRunId"] = evaluation_run_id
            if evidence_output is not None:
                evidence_output.mkdir(parents=True, exist_ok=True)
                (evidence_output / f"{case.case_id}.json").write_text(
                    json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            if deepeval_export_dir is not None:
                _write_deepeval_export(case, observation, deepeval_export_dir)
            assertion_results = evaluate_observation(case, observation)
            item["assertions"] = [_redact(result.as_dict(), client.token) for result in assertion_results]
            item["score"] = (sum(result.status == "PASSED" for result in assertion_results) / len(assertion_results)
                              if assertion_results else 0.0)
            item["passed"] = bool(assertion_results) and all(result.status == "PASSED" for result in assertion_results)
            item["evaluationStatus"] = ("FAILED" if any(result.status == "FAILED" for result in assertion_results)
                                        else "INCONCLUSIVE" if any(result.status == "INCONCLUSIVE" for result in assertion_results)
                                        else "PASSED" if assertion_results else "INCONCLUSIVE")
            execution_types = {"phase", "required_tool", "forbidden_tool", "failed_tool", "tool_order",
                               "tool_count", "dependency_success", "dependency_handoff", "retry_limit", "run_status"}
            execution = [result for result in assertion_results if result.assertion_type in execution_types]
            result_gate = [result for result in assertion_results
                           if result.assertion_type not in execution_types and result.assertion_type != "semantic"]
            item["executionGate"] = _gate_status(execution)
            item["resultGate"] = _gate_status(result_gate)
        except EvalTransportError:
            raise
        except EvalError as exc:
            item["error"] = _redact(str(exc), client.token)
        report_cases.append(item)
    passed = all(item["passed"] for item in report_cases)
    evaluation_status = "FAILED" if any(item.get("evaluationStatus") == "FAILED" for item in report_cases) else \
        "INCONCLUSIVE" if any(item.get("evaluationStatus") == "INCONCLUSIVE" for item in report_cases) else "PASSED"
    return {"schemaVersion": "evaluation-report-v1", "suite": "core", "startedAt": started, "finishedAt": _now(),
            "passed": passed, "taskSuccess": bool(report_cases) and all(item.get("taskSuccess", False) for item in report_cases),
            "evaluationStatus": evaluation_status, "cases": report_cases}


def _gate_status(results: list[AssertionResult]) -> str:
    if any(result.status == "FAILED" for result in results):
        return "FAILED"
    if not results or any(result.status == "INCONCLUSIVE" for result in results):
        return "INCONCLUSIVE"
    return "PASSED"


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--token-env", default="AGENTTEAMS_EVAL_TOKEN")
    parser.add_argument("--cases", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--deepeval-export-dir", type=Path,
                        help="optional directory for credential-free DeepEval case exports")
    parser.add_argument("--evaluation-run-id", help="associate the report with a persisted Evaluation Run")
    parser.add_argument("--evidence-output", type=Path,
                        help="optional directory for normalized Evidence Bundle JSON files")
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
                           deepeval_export_dir=args.deepeval_export_dir,
                           evaluation_run_id=args.evaluation_run_id,
                           evidence_output=args.evidence_output)
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return 0 if report["passed"] else 1
    except (EvalError, OSError, ValueError) as exc:
        print(f"evaluation configuration or network error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
