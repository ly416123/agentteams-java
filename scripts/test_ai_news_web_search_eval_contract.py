#!/usr/bin/env python3
"""Contract checks for the L5 AI News + web_search evaluation case."""

from pathlib import Path
import sys
from dataclasses import replace
import yaml

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.agentteams_eval import load_cases


CASE_FILE = ROOT / "eval/cases/ai-news-web-search.yaml"
CASE_ID = "ai-news-web-search-pdf-positive"
AI_NEWS_MANIFEST = ROOT / "deploy/l5/ai-news-mcp.yaml"


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
    assert set(task_input["aggregateMcpTools"]) == {
        "list_subtasks", "get_task_result", "create_task_pdf_artifact"
    }
    assert "childSourceEvidence" in task_input["aggregatePrompt"]
    assert "empty artifacts array" in task_input["aggregatePrompt"]
    assert "ai-agent-mcp-final-deliverable.pdf" in task_input["aggregatePrompt"]
    assert "Return only one JSON object" in task_input["aggregatePrompt"]
    assert "plain Markdown outside the JSON object" in task_input["aggregatePrompt"]
    assert "subtask 2" in task_input["prompt"].lower()
    assert "create_task_pdf_artifact" not in task_input["prompt"]
    assert "Leader" in task_input["prompt"]
    assert "简体中文" in task_input["prompt"]
    assert "简体中文" in task_input["aggregatePrompt"]
    assert "create_task_pdf_artifact exactly once" in task_input["aggregatePrompt"]
    assert "directly to the parent task" in task_input["aggregatePrompt"]

    assertions = case.assertions
    assertion_types = {assertion["type"] for assertion in assertions}
    assert {"phase", "run_status", "dependency_success", "dependency_handoff",
            "required_tool", "source_citation", "artifact_pdf"} <= assertion_types
    required_tools = {item["name"] for item in assertions
                      if item["type"] == "required_tool"}
    assert {"search_news", "web_search", "create_task_pdf_artifact"} <= required_tools
    tool_counts = [item for item in assertions if item["type"] == "tool_count"
                   and item.get("name") == "create_task_pdf_artifact"]
    assert len(tool_counts) == 1 and tool_counts[0]["expected"] == 1
    assert tool_counts[0]["parentRunOnly"] is True

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

    resources = [item for item in yaml.safe_load_all(AI_NEWS_MANIFEST.read_text(encoding="utf-8"))
                 if item]
    deployment = next(item for item in resources
                      if item.get("kind") == "Deployment"
                      and item["metadata"]["name"] == "ai-news-mcp")
    env = {item["name"]: item for item in deployment["spec"]["template"]["spec"]["containers"][0]["env"]}
    assert env["AGENTTEAMS_CONTROL_PLANE_URL"]["value"] == (
        "http://agentteams-agentteams-java-control-plane:8080")
    assert env["AGENTTEAMS_TASK_MCP_TOKEN"]["valueFrom"]["secretKeyRef"] == {
        "name": "agentteams-mcp-tools", "key": "mcp-tools-token"}

    evidence_egress = next(item for item in resources
                           if item.get("kind") == "NetworkPolicy"
                           and item["metadata"]["name"] == "ai-news-mcp-evidence-egress")
    assert evidence_egress["spec"]["podSelector"]["matchLabels"] == {
        "app.kubernetes.io/name": "ai-news-mcp"}
    assert {rule["ports"][0]["port"] for rule in evidence_egress["spec"]["egress"]
            if rule.get("to") and rule["to"][0].get("podSelector")} >= {6379, 8080}
    assert any(rule.get("to") and rule["to"][0].get("namespaceSelector", {}).get("matchLabels", {}).get(
        "kubernetes.io/metadata.name") == "kube-system" for rule in evidence_egress["spec"]["egress"])

    evidence_ingress = next(item for item in resources
                            if item.get("kind") == "NetworkPolicy"
                            and item["metadata"]["name"] == "ai-news-control-plane-ingress")
    assert evidence_ingress["spec"]["podSelector"]["matchLabels"] == {
        "app.kubernetes.io/name": "agentteams-control-plane"}
    assert evidence_ingress["spec"]["ingress"][0]["from"][0]["podSelector"]["matchLabels"] == {
        "app.kubernetes.io/name": "ai-news-mcp"}
    assert evidence_ingress["spec"]["ingress"][0]["ports"] == [
        {"protocol": "TCP", "port": 8080}]

    print("AI News + web_search evaluation contract passed")


if __name__ == "__main__":
    main()
