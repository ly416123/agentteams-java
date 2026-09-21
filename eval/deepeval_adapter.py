#!/usr/bin/env python3
"""Optional DeepEval adapter for AgentTeams observations.

The AgentTeams Runner remains the source of truth for deterministic gates.  This
module only translates a previously collected observation into a DeepEval
``LLMTestCase`` when the optional dependency is installed.  The normalisation
path has no DeepEval import, so offline CI can validate it without model
credentials or an LLM provider.
"""

from __future__ import annotations

import argparse
import importlib
import inspect
import json
import re
import sys
from pathlib import Path
from typing import Any, Mapping, Sequence

import yaml

# Make the direct `python3 eval/deepeval_adapter.py ...` entrypoint work from
# any current directory without importing DeepEval or requiring installation.
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.agentteams_eval import EvalCase, EvalError, load_cases


class DeepEvalUnavailable(EvalError):
    """Raised when a caller requests DeepEval without installing it."""


_METRIC_CLASSES = {
    "answer_relevancy": "AnswerRelevancyMetric",
    "faithfulness": "FaithfulnessMetric",
    "tool_correctness": "ToolCorrectnessMetric",
}


_BEARER_RE = re.compile(r"(?i)\bbearer\s+[^\s,;]+")
_SECRET_QUERY_RE = re.compile(
    r"(?i)([?&](?:x-amz-[^=&\s]+|signature|token|api[_-]?key|password)=)[^&\s]+"
)
_SECRET_ASSIGNMENT_RE = re.compile(
    r"(?i)\b(api[_-]?key|password|secret|token)\s*[:=]\s*[^\s,;]+"
)


def _sanitize_text(value: Any) -> str:
    """Keep judge input useful while removing common credential forms."""
    text = "" if value is None else str(value)
    text = _BEARER_RE.sub("Bearer [REDACTED]", text)
    text = _SECRET_QUERY_RE.sub(r"\1[REDACTED]", text)
    return _SECRET_ASSIGNMENT_RE.sub(r"\1=[REDACTED]", text)


def _payload(event: Mapping[str, Any]) -> dict[str, Any]:
    value = event.get("payload", event.get("data", {}))
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return {}
    return dict(value) if isinstance(value, Mapping) else {}


def _prompt(case: EvalCase) -> str:
    task = case.task
    input_json = task.get("inputJson")
    if isinstance(input_json, Mapping) and input_json.get("prompt") is not None:
        return _sanitize_text(input_json["prompt"])
    return _sanitize_text(task.get("prompt", ""))


def _actual_output(observation: Mapping[str, Any]) -> str:
    result = observation.get("result") or {}
    values: list[str] = []
    if isinstance(result, Mapping):
        for key in ("summary", "output", "content"):
            if result.get(key) is not None:
                values.append(_sanitize_text(result[key]))
        # Use downloaded artifact content only.  Artifact metadata can contain
        # presigned URLs and is intentionally excluded from judge input.
        for artifact in result.get("artifacts", []) or []:
            if isinstance(artifact, Mapping) and artifact.get("content") is not None:
                values.append(_sanitize_text(artifact["content"]))
    return "\n".join(value for value in values if value)


def _tool_names(observation: Mapping[str, Any]) -> list[str]:
    names: list[str] = []
    seen: set[str] = set()
    events = observation.get("events", []) or []
    for preferred in ("tool.called", "tool.finished", "tool.completed"):
        for event in events:
            if not isinstance(event, Mapping):
                continue
            event_type = str(event.get("eventType", event.get("type", "")))
            if event_type != preferred:
                continue
            payload = _payload(event)
            name = payload.get("tool") or payload.get("toolName") or payload.get("name")
            if name and str(name) not in seen:
                seen.add(str(name))
                names.append(str(name))
        if names:
            break
    return names


def _retrieval_context(observation: Mapping[str, Any]) -> list[str]:
    """Extract bounded, textual tool results for metrics such as faithfulness."""
    contexts: list[str] = []
    seen: set[str] = set()
    for event in observation.get("events", []) or []:
        if not isinstance(event, Mapping):
            continue
        event_type = str(event.get("eventType", event.get("type", "")))
        if event_type not in {"tool.finished", "tool.completed"}:
            continue
        payload = _payload(event)
        for key in ("output", "result", "content", "response", "text"):
            value = payload.get(key)
            if isinstance(value, Mapping):
                value = json.dumps(value, ensure_ascii=False, sort_keys=True)
            if value is None:
                continue
            text = _sanitize_text(value).strip()
            if text and text not in seen:
                seen.add(text)
                contexts.append(text[:8000])
    return contexts


def _expected_tools(case: EvalCase) -> list[str]:
    return [
        str(assertion["name"])
        for assertion in case.assertions
        if assertion.get("type") == "required_tool" and assertion.get("name")
    ]


def _expected_output(case: EvalCase) -> str | None:
    values = [
        str(assertion["value"])
        for assertion in case.assertions
        if assertion.get("type") == "output_contains" and assertion.get("value") is not None
    ]
    return "\n".join(values) if values else None


def _number(mapping: Mapping[str, Any], keys: Sequence[str]) -> float | None:
    for key in keys:
        value = mapping.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
    return None


def load_rubric(path: str | Path) -> dict[str, Any]:
    """Load and validate a versioned, credential-free semantic rubric."""
    try:
        value = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        raise EvalError(f"cannot read rubric YAML {path}: {exc}") from exc
    if not isinstance(value, Mapping):
        raise EvalError("rubric must contain an object")
    rubric = dict(value)
    if rubric.get("schemaVersion") != "deepeval-rubric-v1":
        raise EvalError("rubric schemaVersion must be deepeval-rubric-v1")
    if rubric.get("hardGate") != "deterministic":
        raise EvalError("rubric hardGate must be deterministic")
    cases = rubric.get("cases")
    if not isinstance(cases, list) or not cases:
        raise EvalError("rubric cases must be a non-empty list")
    seen_cases: set[str] = set()
    normalized_cases: list[dict[str, Any]] = []
    for index, item in enumerate(cases):
        if not isinstance(item, Mapping):
            raise EvalError(f"rubric case[{index}] must be an object")
        case = dict(item)
        case_id = str(case.get("id", "")).strip()
        if not case_id:
            raise EvalError(f"rubric case[{index}] requires id")
        if case_id in seen_cases:
            raise EvalError(f"duplicate rubric case id: {case_id}")
        seen_cases.add(case_id)
        metrics = case.get("metrics", [])
        if not isinstance(metrics, list):
            raise EvalError(f"rubric case {case_id}.metrics must be a list")
        seen_metrics: set[str] = set()
        normalized_metrics: list[dict[str, Any]] = []
        for metric_index, raw_metric in enumerate(metrics):
            if not isinstance(raw_metric, Mapping):
                raise EvalError(f"rubric case {case_id}.metric[{metric_index}] must be an object")
            metric = dict(raw_metric)
            metric_id = str(metric.get("id", "")).strip()
            if metric_id not in _METRIC_CLASSES:
                raise EvalError(f"unsupported DeepEval metric: {metric_id}")
            if metric_id in seen_metrics:
                raise EvalError(f"duplicate metric {metric_id} in rubric case {case_id}")
            seen_metrics.add(metric_id)
            threshold = metric.get("threshold")
            if isinstance(threshold, bool) or not isinstance(threshold, (int, float)):
                raise EvalError(f"metric {metric_id} threshold must be a number")
            if not 0.0 <= float(threshold) <= 1.0:
                raise EvalError(f"metric {metric_id} threshold must be between 0 and 1")
            metric["id"] = metric_id
            metric["threshold"] = float(threshold)
            metric["includeReason"] = bool(metric.get("includeReason", True))
            normalized_metrics.append(metric)
        case["id"] = case_id
        case["metrics"] = normalized_metrics
        normalized_cases.append(case)
    rubric["cases"] = normalized_cases
    return rubric


def build_deepeval_metrics(rubric: Mapping[str, Any], case_id: str,
                           model: Any = None) -> list[Any]:
    """Build configured DeepEval metrics for one case on demand."""
    try:
        deepeval_metrics = importlib.import_module("deepeval.metrics")
    except ImportError as exc:
        raise DeepEvalUnavailable(
            "DeepEval is not installed; run `python -m pip install deepeval` in an optional environment"
        ) from exc
    cases = rubric.get("cases", [])
    case = next((item for item in cases if isinstance(item, Mapping) and item.get("id") == case_id), None)
    if case is None:
        raise EvalError(f"unknown rubric case id: {case_id}")
    metrics: list[Any] = []
    for spec in case.get("metrics", []):
        metric_id = str(spec["id"])
        metric_class = getattr(deepeval_metrics, _METRIC_CLASSES[metric_id], None)
        if metric_class is None:
            raise EvalError(f"DeepEval does not provide metric class for {metric_id}")
        values: dict[str, Any] = {
            "threshold": float(spec["threshold"]),
            "include_reason": bool(spec.get("includeReason", True)),
        }
        if model is not None:
            values["model"] = model
        metrics.append(metric_class(**_accepted_kwargs(metric_class, values)))
    return metrics


def build_test_case_payload(case: EvalCase, observation: Mapping[str, Any]) -> dict[str, Any]:
    """Build a credential-free, JSON-serialisable DeepEval case payload."""
    run = observation.get("run") if isinstance(observation.get("run"), Mapping) else {}
    payload: dict[str, Any] = {
        "input": _prompt(case),
        "actual_output": _actual_output(observation),
        "tools_called": [{"name": name} for name in _tool_names(observation)],
        "expected_tools": [{"name": name} for name in _expected_tools(case)],
    }
    retrieval_context = _retrieval_context(observation)
    if retrieval_context:
        payload["retrieval_context"] = retrieval_context
    expected_output = _expected_output(case)
    if expected_output is not None:
        payload["expected_output"] = _sanitize_text(expected_output)
    token_cost = _number(run, ("tokenCost", "token_cost", "totalTokens", "total_tokens"))
    completion_time = _number(run, ("completionTime", "completion_time", "durationSeconds", "duration_seconds"))
    if token_cost is not None:
        payload["token_cost"] = token_cost
    if completion_time is not None:
        payload["completion_time"] = completion_time
    return payload


def _accepted_kwargs(constructor: Any, values: Mapping[str, Any]) -> dict[str, Any]:
    """Filter kwargs for DeepEval releases with different constructor fields."""
    try:
        signature = inspect.signature(constructor)
    except (TypeError, ValueError):
        return dict(values)
    if any(parameter.kind == inspect.Parameter.VAR_KEYWORD
           for parameter in signature.parameters.values()):
        return dict(values)
    return {key: value for key, value in values.items() if key in signature.parameters}


def _make_tool_call(tool_class: Any, name: str, tool_call_type: Any = None) -> Any:
    values: dict[str, Any] = {"name": name}
    if tool_call_type is not None:
        try:
            parameters = inspect.signature(tool_class).parameters
        except (TypeError, ValueError):
            parameters = {}
        if "type" in parameters:
            # DeepEval 4.x names this field `type`.
            values["type"] = tool_call_type
        else:
            values["tool_call_type"] = tool_call_type
    return tool_class(**_accepted_kwargs(tool_class, values))


def build_deepeval_test_case(case: EvalCase, observation: Mapping[str, Any]) -> Any:
    """Construct a real DeepEval ``LLMTestCase`` on demand."""
    try:
        from deepeval.test_case import LLMTestCase, ToolCall  # type: ignore
    except ImportError as exc:
        raise DeepEvalUnavailable(
            "DeepEval is not installed; run `python -m pip install deepeval` in an optional environment"
        ) from exc

    try:
        from deepeval.test_case import ToolCallType  # type: ignore
        mcp_type = getattr(ToolCallType, "MCP", None)
    except ImportError:
        mcp_type = None

    payload = build_test_case_payload(case, observation)
    values: dict[str, Any] = {
        "input": payload["input"],
        "actual_output": payload["actual_output"],
        "tools_called": [_make_tool_call(ToolCall, item["name"], mcp_type)
                         for item in payload["tools_called"]],
        "expected_tools": [_make_tool_call(ToolCall, item["name"], mcp_type)
                           for item in payload["expected_tools"]],
    }
    for key in ("expected_output", "retrieval_context", "token_cost", "completion_time"):
        if key in payload:
            values[key] = payload[key]
    return LLMTestCase(**_accepted_kwargs(LLMTestCase, values))


def evaluate_with_deepeval(test_case: Any, metrics: Sequence[Any]) -> Any:
    """Run optional DeepEval metrics; callers choose judge model and thresholds."""
    try:
        from deepeval import evaluate  # type: ignore
    except ImportError as exc:
        raise DeepEvalUnavailable(
            "DeepEval is not installed; run `python -m pip install deepeval` in an optional environment"
        ) from exc
    return evaluate(test_cases=[test_case], metrics=list(metrics))


def load_observation(path: str | Path) -> dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EvalError(f"cannot read observation JSON {path}: {exc}") from exc
    if not isinstance(value, Mapping):
        raise EvalError("observation JSON must contain an object")
    return dict(value)


def render_export(case: EvalCase, observation: Mapping[str, Any]) -> dict[str, Any]:
    return {"schemaVersion": "deepeval-v1", "caseId": case.case_id,
            "testCase": build_test_case_payload(case, observation)}


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", required=True, help="canonical YAML case file")
    parser.add_argument("--case-id", required=True)
    parser.add_argument("--observation", required=True, help="raw AgentTeams observation JSON")
    parser.add_argument("--output", required=True, help="credential-free DeepEval JSON export")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        cases = load_cases(args.cases)
        case = next((item for item in cases if item.case_id == args.case_id), None)
        if case is None:
            raise EvalError(f"unknown case id: {args.case_id}")
        exported = render_export(case, load_observation(args.observation))
        Path(args.output).write_text(json.dumps(exported, ensure_ascii=False, indent=2) + "\n",
                                     encoding="utf-8")
        return 0
    except (EvalError, OSError, ValueError) as exc:
        print(f"DeepEval adapter error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
