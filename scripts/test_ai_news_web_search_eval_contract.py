#!/usr/bin/env python3
"""Contract checks for the L5 AI News + web_search evaluation case."""

from pathlib import Path
import sys
from dataclasses import replace

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.agentteams_eval import load_cases


CASE_FILE = ROOT / "eval/cases/ai-news-web-search.yaml"
CASE_ID = "ai-news-web-search-pdf-positive"


def validate_case(case) -> None:
    assert case.task["scope"] == {
        "tenant": "tenant-a",
        "project": "project-a",
        "team": "team-a",
    }
    assert case.task["taskType"] == "agentscope"

    task_input = case.task["inputJson"]
    assert task_input["requiredMcpTools"] == ["plan_subtasks"]
    assert set(task_input["executorMcpTools"]) == {"search_news", "web_search"}
    assert task_input["aggregateMcpTools"] == ["create_task_pdf_artifact"]
    assert "childSourceEvidence" in task_input["aggregatePrompt"]
    assert "Do not return a JSON artifacts envelope" in task_input["aggregatePrompt"]

    assertions = case.assertions
    assertion_types = {assertion["type"] for assertion in assertions}
    assert {"phase", "run_status", "dependency_success", "dependency_handoff",
            "required_tool", "source_citation", "artifact_pdf"} <= assertion_types
    required_tools = {item["name"] for item in assertions
                      if item["type"] == "required_tool"}
    assert {"search_news", "web_search"} <= required_tools

    pdf_assertions = [item for item in assertions if item["type"] == "artifact_pdf"]
    assert len(pdf_assertions) == 1
    assert pdf_assertions[0]["exactlyOne"] is True
    assert pdf_assertions[0]["durable"] is True
    assert pdf_assertions[0]["downloadable"] is True


def main() -> None:
    cases = load_cases(CASE_FILE)
    assert [case.case_id for case in cases] == [CASE_ID]
    case = cases[0]
    validate_case(case)

    task_input = dict(case.task["inputJson"])
    task_input["executorMcpTools"] = ["web_search"]
    task = dict(case.task)
    task["inputJson"] = task_input
    try:
        validate_case(replace(case, task=task))
    except AssertionError:
        pass
    else:
        raise AssertionError("contract must reject a case missing search_news")

    print("AI News + web_search evaluation contract passed")


if __name__ == "__main__":
    main()
