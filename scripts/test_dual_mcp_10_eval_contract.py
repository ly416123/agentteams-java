#!/usr/bin/env python3
"""Contract checks for the ten distinct dual-MCP L5 evaluation cases."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.agentteams_eval import load_cases


CASE_FILE = ROOT / "eval/cases/ai-news-web-search-10.yaml"


def main() -> None:
    cases = load_cases(CASE_FILE)
    assert len(cases) == 10
    assert len({case.case_id for case in cases}) == 10
    seen_prompts = set()
    seen_final_files = set()
    for case in cases:
        task_input = case.task["inputJson"]
        prompt = task_input["prompt"]
        assert prompt not in seen_prompts
        seen_prompts.add(prompt)
        assert "search_news" in prompt and "web_search" in prompt
        assert "简体中文" in prompt
        assert "至少5条近7天新闻" in prompt
        assert "至少2项由官方原文核实" in prompt
        assert "多词按全部关键词交集匹配" in prompt
        assert "不要传完整问题或多个概念组成的长查询" in prompt
        assert "不能因一个长查询为空判断没有新闻" in prompt
        assert "避免只含一小段文字的独立末页" in prompt
        assert "正文必须直接回答研究问题" in prompt
        assert "不得在 PDF 正文提及" in prompt
        assert "taskId" in prompt and "sourceFileId" in prompt
        assert "不要把上游研究笔记当作最终报告" in prompt
        assert "以研究结论开篇" in prompt
        assert "仅返回文本，不创建文件" in prompt
        assert "中间研究 PDF" not in prompt
        assert "在 plan_subtasks 中为每个子任务单独指定 requiredMcpTools" in prompt
        assert "子任务1和子任务2均不得启用或调用 create_task_pdf_artifact" in prompt
        if case.case_id == "dual-mcp-10-agent-evaluation":
            assert "子任务2标题必须包含完整文件名 agent-evaluation-final.pdf" in prompt
        assert "简体中文" in task_input["aggregatePrompt"]
        assert ("In the PDF's Sources section, include at least one complete original URL copied verbatim "
                "from a successful web_search result for this task or one of its successful subtasks."
                in task_input["aggregatePrompt"])
        assert set(task_input["executorMcpTools"]) == {"search_news", "web_search"}
        assert set(task_input["aggregateMcpTools"]) == {
            "list_subtasks", "get_task_result", "create_task_pdf_artifact"
        }
        assert "childSourceEvidence" in task_input["aggregatePrompt"]
        assert "sourceFileId" in task_input["aggregatePrompt"]
        assert "exactly one JSON object" in task_input["aggregatePrompt"]
        assert "never call write_file" in task_input["aggregatePrompt"]
        assert "create_task_pdf_artifact exactly once" in task_input["aggregatePrompt"]
        assert "uploads the PDF directly to this parent task" in task_input["aggregatePrompt"]
        assert "Do not include subtask workflow notes" in task_input["aggregatePrompt"]
        assert "Do not copy operational metadata from subtask results into the PDF body" in task_input["aggregatePrompt"]
        assert "The PDF itself must contain the complete answer" in task_input["aggregatePrompt"]
        assert "Do not mention taskId, sourceTaskId, sourceFileId" in task_input["aggregatePrompt"]
        assert "numbered news entries" in task_input["aggregatePrompt"]
        assert "publication date, publisher, original URL, and a concise finding" in task_input["aggregatePrompt"]
        assert "Do not use wide Markdown tables" in task_input["aggregatePrompt"]
        assert "state the supported count and search limitation" in task_input["aggregatePrompt"]
        final_files = [word for word in task_input["aggregatePrompt"].split()
                       if word.endswith("-final.pdf")]
        assert len(final_files) == 1
        assert final_files[0] not in seen_final_files
        seen_final_files.add(final_files[0])
        assertion_types = {item["type"] for item in case.assertions}
        assert {"phase", "run_status", "dependency_success", "dependency_handoff",
                "source_citation", "artifact_pdf", "artifact_pdf_quality"} <= assertion_types
        required_tools = {item["name"] for item in case.assertions
                          if item["type"] == "required_tool"}
        assert {"search_news", "web_search", "create_task_pdf_artifact"} <= required_tools
        pdf = [item for item in case.assertions if item["type"] == "artifact_pdf"]
        assert len(pdf) == 1 and pdf[0]["exactlyOne"] and pdf[0]["durable"] and pdf[0]["downloadable"]
        pdf_quality = [item for item in case.assertions if item["type"] == "artifact_pdf_quality"]
        assert len(pdf_quality) == 1 and pdf_quality[0]["minimumCjkRatio"] >= 0.25
        tool_counts = [item for item in case.assertions if item["type"] == "tool_count"
                       and item.get("name") == "create_task_pdf_artifact"]
        assert len(tool_counts) == 1 and tool_counts[0]["expected"] == 1
        assert tool_counts[0]["parentRunOnly"] is True
        assert case.limits["timeoutSeconds"] >= 360
    print("10-case dual MCP evaluation contract passed")


if __name__ == "__main__":
    main()
