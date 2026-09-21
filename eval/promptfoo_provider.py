"""Promptfoo provider that runs one canonical AgentTeams evaluation case."""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.agentteams_eval import (  # noqa: E402
    AgentTeamsClient,
    EvalCase,
    EvalError,
    evaluate_observation,
    load_cases,
)


def call_api(prompt: str, options: dict[str, Any], context: dict[str, Any]) -> dict[str, Any]:
    """Execute the case selected by Promptfoo's generated test variables."""
    variables = context.get("vars", {})
    case_id = str(variables.get("case_id", "")).strip()
    cases = load_cases(Path(__file__).with_name("cases") / "core.yaml")
    case = next((item for item in cases if item.case_id == case_id), None)
    if case is None:
        raise EvalError(f"unknown Promptfoo case: {case_id}")

    task = dict(case.task)
    input_json = dict(task.get("inputJson", {}))
    input_json["prompt"] = prompt
    task["inputJson"] = input_json
    case = EvalCase(case.case_id, case.name, task, case.assertions, case.limits)

    base_url = os.environ.get("AGENTTEAMS_EVAL_BASE_URL", "").strip()
    token = os.environ.get("AGENTTEAMS_EVAL_TOKEN", "")
    if not base_url or not token:
        raise EvalError("AGENTTEAMS_EVAL_BASE_URL and AGENTTEAMS_EVAL_TOKEN are required")
    client = AgentTeamsClient(base_url, token)
    created = client.create_task(case)
    task_id = str(created.get("id", ""))
    if not task_id:
        raise EvalError("create task response has no task id")
    client.queue_task(task_id, int(created.get("version", 0)))
    client.wait_for_terminal(task_id, float(case.limits.get("timeoutSeconds", 180)), 3.0)
    run = client.get_run(task_id)
    run_id = str(run.get("id") or run.get("runId") or "")
    if not run_id:
        raise EvalError("run response has no run id")
    results = evaluate_observation(case, client.get_observation(task_id, run_id))
    passed = bool(results) and all(result.passed for result in results)
    score = sum(result.passed for result in results) / len(results) if results else 0.0
    return {
        "output": json.dumps({
            "caseId": case.case_id,
            "taskId": task_id,
            "runId": run_id,
            "passed": passed,
            "score": score,
            "assertions": [result.as_dict() for result in results],
        }, ensure_ascii=False),
        "metadata": {"caseId": case.case_id, "taskId": task_id, "runId": run_id},
    }
